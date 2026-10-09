package com.accubook.app.company;

import com.accubook.app.provisioning.CompanyProvisioning;
import com.accubook.app.schema.Schemas;
import com.accubook.persistence.connection.Database;
import com.accubook.persistence.location.StorageLocationCheck;
import com.accubook.persistence.lock.ExclusiveLock;
import com.accubook.persistence.migration.IntegrityChecks;
import com.accubook.persistence.migration.MigrationRunner;
import com.accubook.persistence.paths.AppPaths;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Creating, opening and switching companies.
 *
 * <p>Implements decision B-6: each company is a separate SQLite file, opened one
 * at a time for writing. The application holds at most one open company, so
 * cached state cannot be attributed to the wrong set of books.
 */
public final class CompanyService implements AutoCloseable {

    /** April, the Indian convention. Configurable per company in a later phase. */
    private static final int DEFAULT_FISCAL_YEAR_START_MONTH = 4;

    private final AppPaths paths;
    private final Database appDatabase;
    private final CompanyRegistry registry;
    private final MigrationRunner companyMigrations;

    private final ExclusiveLock installationLock;

    private Database openCompanyDatabase;
    private Company openCompany;
    private ExclusiveLock companyLock;

    private CompanyService(AppPaths paths, Database appDatabase, ExclusiveLock installationLock) {
        this.paths = paths;
        this.appDatabase = appDatabase;
        this.installationLock = installationLock;
        this.registry = new CompanyRegistry(appDatabase);
        this.companyMigrations = new MigrationRunner(Schemas.companyDatabase(), paths.backupsDir());
    }

    /**
     * Open the installation rooted at {@code dataRoot}.
     *
     * <p>Takes a plain {@link Path} rather than an {@code AppPaths} so that
     * callers - the launcher, the UI, tests - do not have to depend on the
     * persistence layer just to say where the data lives. The on-disk layout is
     * an implementation detail of this layer, not part of its contract.
     */
    public static CompanyService start(Path dataRoot) throws SQLException, IOException {
        return start(AppPaths.at(dataRoot));
    }

    /**
     * Open the installation at the platform's default location.
     *
     * <p>{@code %LOCALAPPDATA%} on Windows, {@code ~/Library/Application
     * Support} on macOS, {@code $XDG_DATA_HOME} on Linux - always outside the
     * installation directory, so an update or uninstall cannot touch customer
     * books.
     */
    public static CompanyService startAtDefaultLocation() throws SQLException, IOException {
        return start(AppPaths.forCurrentPlatform());
    }

    /**
     * Open the installation: create the data directories, open {@code app.db}
     * and migrate it.
     *
     * <p>{@code app.db} migrates at launch. Company files migrate on open
     * instead - a company file may be absent at update time (external drive,
     * archived) and must still migrate correctly whenever it appears.
     */
    static CompanyService start(AppPaths paths) throws SQLException, IOException {
        // Decision P2-3: refuse, do not warn. A network share or sync folder
        // will corrupt the database, so there is nothing useful to continue to.
        StorageLocationCheck.requireSupported(paths.root());

        paths.createDirectories();

        // One application instance per installation. Two instances would each
        // hold their own view of the open company and its state.
        ExclusiveLock lock = ExclusiveLock.acquire(paths.root().resolve("accubook.lock"));
        try {
            Database appDatabase = Database.open(paths.appDatabase());
            new MigrationRunner(Schemas.appDatabase(), paths.backupsDir()).migrate(appDatabase);
            return new CompanyService(paths, appDatabase, lock);
        } catch (RuntimeException | SQLException e) {
            lock.close();
            throw e;
        }
    }

    public List<Company> companies() throws SQLException {
        return registry.findAll();
    }

    public Optional<Company> currentCompany() {
        return Optional.ofNullable(openCompany);
    }

    /**
     * Create a company: its directory, its database, its schema, then the
     * registry entry.
     *
     * <p>The registry row is written last on purpose. If creation fails partway,
     * an unregistered directory is recoverable; a registry row pointing at a
     * file that does not exist is a broken installation the user cannot fix.
     */
    public Company createCompany(String name) throws SQLException, IOException {
        String id = Ids.newIdString();
        paths.createCompanyDirectories(id);

        Instant createdAt = Instant.now();
        int schemaVersion;

        try (Database companyDatabase = Database.open(paths.companyDatabase(id))) {
            schemaVersion = companyMigrations.migrate(companyDatabase).toVersion();
            // Identity, chart of accounts, voucher types and the opening fiscal
            // year in one transaction, so a half-provisioned company cannot
            // survive a failure.
            companyDatabase.inTransaction(connection -> {
                CompanyProvisioning.provision(connection, id, name,
                        DEFAULT_FISCAL_YEAR_START_MONTH, LocalDate.now());
                return null;
            });
        }

        Company company = new Company(id, name, schemaVersion, createdAt, null);
        registry.register(company);
        return company;
    }

    /**
     * Open a company for writing, migrating it if the file is behind.
     *
     * <p>Any currently open company is closed first: exactly one company is open
     * for writing at a time.
     */
    public Company openCompany(String id) throws SQLException {
        Company company = registry.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such company: " + id));

        closeOpenCompany();

        // A second process must not open the same books, even though SQLite
        // would keep the file itself intact.
        ExclusiveLock lock = ExclusiveLock.acquire(
                paths.companyDir(id).resolve("company.lock"));

        Database database;
        try {
            database = Database.open(paths.companyDatabase(id));
        } catch (RuntimeException | SQLException e) {
            lock.close();
            throw e;
        }
        this.companyLock = lock;
        // Cheap structural check on every open; the expensive integrity_check is
        // reserved for backups and post-migration.
        IntegrityChecks.requireQuickCheckPasses(database);

        int version = companyMigrations.migrate(database).toVersion();

        this.openCompanyDatabase = database;
        this.openCompany = new Company(company.id(), company.name(), version,
                company.createdAt(), Instant.now());
        registry.markOpened(id, version);
        return this.openCompany;
    }

    /**
     * The open company's database.
     *
     * <p>Package-private deliberately. Handing a raw {@code Database} to the UI
     * or the launcher would let any layer open its own transaction and bypass
     * the use-case boundary - the same convention-based gap that let the
     * reference application's Tally import bypass its posting invariant.
     * Callers outside this package go through use-case methods.
     */
    Database companyDatabase() {
        if (openCompanyDatabase == null) {
            throw new IllegalStateException("No company is open");
        }
        return openCompanyDatabase;
    }

    /**
     * Ledger balances, computed from the journal.
     *
     * <p>Decision P2-2: balances are derived, never cached as the source of
     * truth. The reference application keeps two independent running balances -
     * one on the ledger, one on the bank account - maintained by separate code
     * paths with nothing reconciling them, so they can drift apart silently.
     *
     * <p>Deriving is affordable because money is stored as scaled integers:
     * SQLite's {@code SUM()} over an INTEGER column is exact, so the aggregate
     * can run in the database rather than pulling every row into memory as the
     * reference does. If profiling later justifies a cache, it becomes a
     * rebuildable optimisation with a verification routine - not the truth.
     *
     * <p>The balance is signed in the account's natural direction, so it reads
     * positive the way an accountant expects: assets and expenses grow with
     * debits, liabilities, income and equity with credits.
     */
    public List<LedgerBalance> ledgerBalances() throws SQLException {
        String sql = """
                SELECT l.id,
                       l.name,
                       g.nature,
                       COALESCE(SUM(e.debit_amount_e4), 0)  AS debits,
                       COALESCE(SUM(e.credit_amount_e4), 0) AS credits
                FROM ledger l
                JOIN ledger_group g ON g.id = l.group_id
                LEFT JOIN voucher_entry e ON e.ledger_id = l.id
                LEFT JOIN voucher v ON v.id = e.voucher_id
                     AND v.is_posted = 1
                GROUP BY l.id, l.name, g.nature
                ORDER BY l.name
                """;
        List<LedgerBalance> balances = new ArrayList<>();
        try (var connection = companyDatabase().openReadConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String nature = rs.getString("nature");
                long debits = rs.getLong("debits");
                long credits = rs.getLong("credits");
                boolean debitNatured = "ASSETS".equals(nature) || "EXPENSES".equals(nature);
                long signed = debitNatured ? debits - credits : credits - debits;
                balances.add(new LedgerBalance(
                        rs.getString("id"),
                        rs.getString("name"),
                        nature,
                        MoneyMapper.fromStored(signed, Scale.MONEY)));
            }
        }
        return balances;
    }

    /**
     * Total debits and credits across all posted entries.
     *
     * <p>The trial-balance invariant. Asserted after migrations and backups
     * because a migration can leave a structurally valid database whose books no
     * longer balance - a mis-scaled column, a dropped entry - and only an
     * accounting check catches that.
     */
    public TrialBalanceTotals trialBalanceTotals() throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(e.debit_amount_e4), 0)  AS debits,
                       COALESCE(SUM(e.credit_amount_e4), 0) AS credits
                FROM voucher_entry e
                JOIN voucher v ON v.id = e.voucher_id
                WHERE v.is_posted = 1
                """;
        try (var connection = companyDatabase().openReadConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new TrialBalanceTotals(
                    MoneyMapper.fromStored(rs.getLong("debits"), Scale.MONEY),
                    MoneyMapper.fromStored(rs.getLong("credits"), Scale.MONEY));
        }
    }

    /** The company's chart of accounts, in display order. */
    public List<LedgerSummary> chartOfAccounts() throws SQLException {
        String sql = """
                SELECT l.id, l.system_code, l.name, g.name AS group_name, g.nature
                FROM ledger l
                JOIN ledger_group g ON g.id = l.group_id
                ORDER BY g.sequence, l.name
                """;
        List<LedgerSummary> ledgers = new ArrayList<>();
        try (var connection = companyDatabase().openReadConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ledgers.add(new LedgerSummary(
                        rs.getString("id"),
                        rs.getString("system_code"),
                        rs.getString("name"),
                        rs.getString("group_name"),
                        rs.getString("nature")));
            }
        }
        return ledgers;
    }

    /** Identity read from the company file itself, not from the registry (P2-4). */
    public Optional<CompanyIdentity> readIdentityFromFile(String companyId) throws SQLException {
        try (Database database = Database.open(paths.companyDatabase(companyId));
             var connection = database.openReadConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT id, name, fiscal_year_start_month, created_at FROM company_info");
             ResultSet rs = ps.executeQuery()) {
            return rs.next()
                    ? Optional.of(new CompanyIdentity(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getInt("fiscal_year_start_month"),
                            Instant.ofEpochMilli(rs.getLong("created_at"))))
                    : Optional.empty();
        }
    }

    /** A ledger balance in its natural direction. */
    public record LedgerBalance(String ledgerId, String name, String nature, BigDecimal balance) {
    }

    /** Trial balance totals; {@link #balanced()} is the invariant. */
    public record TrialBalanceTotals(BigDecimal totalDebits, BigDecimal totalCredits) {
        public boolean balanced() {
            return totalDebits.compareTo(totalCredits) == 0;
        }
    }

    /** A ledger and the group it belongs to. */
    public record LedgerSummary(String id, String systemCode, String name,
                                String groupName, String nature) {
    }

    /** Company identity as recorded inside the company file. */
    public record CompanyIdentity(String id, String name, int fiscalYearStartMonth, Instant createdAt) {
    }

    private Company requireOpenCompany() {
        if (openCompany == null) {
            throw new IllegalStateException("No company is open");
        }
        return openCompany;
    }

    private void closeOpenCompany() throws SQLException {
        if (openCompanyDatabase != null) {
            openCompanyDatabase.close();
            openCompanyDatabase = null;
            openCompany = null;
        }
        if (companyLock != null) {
            companyLock.close();
            companyLock = null;
        }
    }

    /** True if the company's database file exists on disk. */
    public boolean companyFileExists(String id) {
        return Files.exists(paths.companyDatabase(id));
    }

    /** Root directory of this installation's data. */
    public Path dataRoot() {
        return paths.root();
    }

    @Override
    public void close() throws SQLException {
        closeOpenCompany();
        appDatabase.close();
        installationLock.close();
    }
}
