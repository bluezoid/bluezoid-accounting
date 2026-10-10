package com.accubook.app.company;

import com.accubook.app.entitlement.ModuleWriteAuthorization;
import com.accubook.app.module.DatabaseModuleTransactions;
import com.accubook.app.provisioning.CompanyProvisioning;
import com.accubook.app.schema.Schemas;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleStateProvider;
import com.accubook.module.ModuleTransactions;
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

    /** Which module owns which table, and whether each module may write. */
    private final ModuleRegistry modules;
    private final ModuleStateProvider moduleStates;

    private Database openCompanyDatabase;
    private Company openCompany;
    private ExclusiveLock companyLock;

    private CompanyService(AppPaths paths, Database appDatabase, ExclusiveLock installationLock,
                           ModuleRegistry modules, ModuleStateProvider moduleStates) {
        this.paths = paths;
        this.appDatabase = appDatabase;
        this.installationLock = installationLock;
        this.modules = java.util.Objects.requireNonNull(modules, "modules");
        this.moduleStates = java.util.Objects.requireNonNull(moduleStates, "moduleStates");
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
        return start(AppPaths.at(dataRoot), NO_FEATURE_MODULES, KERNEL_ONLY);
    }

    /**
     * Open the installation with the feature modules this build contains and
     * the entitlement that decides what each may do.
     *
     * <p>Supplied by the composition root. Every company opened through this
     * service has the persistence guard installed from them, so a module that is
     * not {@code ACTIVE} cannot write its tables by any route through here.
     */
    public static CompanyService start(Path dataRoot, ModuleRegistry modules,
                                       ModuleStateProvider moduleStates) throws SQLException, IOException {
        return start(AppPaths.at(dataRoot), modules, moduleStates);
    }

    /** A build with no feature modules: nothing is module-owned. */
    private static final ModuleRegistry NO_FEATURE_MODULES = ModuleRegistry.discover(List.of());

    /** Kernel modules write; anything else is absent. */
    private static final ModuleStateProvider KERNEL_ONLY = moduleId ->
            com.accubook.module.ModuleId.of(moduleId).isKernel() ? ModuleState.ACTIVE : ModuleState.ABSENT;

    /**
     * Open the installation at the platform's default location.
     *
     * <p>{@code %LOCALAPPDATA%} on Windows, {@code ~/Library/Application
     * Support} on macOS, {@code $XDG_DATA_HOME} on Linux - always outside the
     * installation directory, so an update or uninstall cannot touch customer
     * books.
     */
    public static CompanyService startAtDefaultLocation() throws SQLException, IOException {
        return start(AppPaths.forCurrentPlatform(), NO_FEATURE_MODULES, KERNEL_ONLY);
    }

    /** As {@link #startAtDefaultLocation()}, with the build's modules and entitlement. */
    public static CompanyService startAtDefaultLocation(ModuleRegistry modules,
                                                        ModuleStateProvider moduleStates)
            throws SQLException, IOException {
        return start(AppPaths.forCurrentPlatform(), modules, moduleStates);
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
        return start(paths, NO_FEATURE_MODULES, KERNEL_ONLY);
    }

    static CompanyService start(AppPaths paths, ModuleRegistry modules,
                                ModuleStateProvider moduleStates) throws SQLException, IOException {
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
            // The rule pack shipped with this build, unless a newer one is installed.
            com.accubook.tax.RulePacks.forThisBuild(appDatabase).installBundled();
            return new CompanyService(paths, appDatabase, lock, modules, moduleStates);
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

        // Layer 2 of entitlement enforcement. Installed on every open, after
        // migrations (which run unguarded) and before anything else can write.
        // Database's own default is permit-all, so leaving this out would make
        // the guard advisory in the running application.
        database.authorizeWith(new ModuleWriteAuthorization(modules, moduleStates));

        this.openCompanyDatabase = database;
        this.openCompany = new Company(company.id(), company.name(), version,
                company.createdAt(), Instant.now());
        registry.markOpened(id, version);
        return this.openCompany;
    }

    /**
     * Guarded transactions on the open company - what a feature module's use
     * cases are given. Writes pass the persistence guard; there is no route from
     * here to an unguarded transaction.
     */
    public ModuleTransactions openCompanyTransactions() {
        return new DatabaseModuleTransactions(companyDatabase());
    }

    /** The statutory rule pack in force on this installation, verified as it loads (P9-4). */
    public com.accubook.tax.RulePack rules() {
        return com.accubook.tax.RulePacks.forThisBuild(appDatabase).active();
    }

    /** Install a newer signed rule pack from a file - the offline route until the admin portal (P13). */
    public com.accubook.tax.RulePack importRulePack(Path file) {
        return com.accubook.tax.RulePacks.forThisBuild(appDatabase).importFile(file);
    }

    /** Every rule pack installed here, oldest first. */
    public List<com.accubook.tax.RulePacks.Installed> installedRulePacks() {
        return com.accubook.tax.RulePacks.forThisBuild(appDatabase).installed();
    }

    /** The entitlement every module's state is read from. */
    public ModuleStateProvider moduleStates() {
        return moduleStates;
    }

    /**
     * Run a read against the open company.
     *
     * <p>The connection cannot write: it comes from the {@code query_only} read
     * pool and is wrapped by the persistence guard with a refuse-everything
     * authorization, so even {@code PRAGMA query_only = 0} is refused. That is
     * what makes it safe to offer outside this package, where
     * {@link #companyDatabase()} is not - a report can read the books, and
     * nothing that holds this connection can change them.
     */
    public <T> T readOpenCompany(ModuleTransactions.Work<T> work) throws SQLException {
        return new DatabaseModuleTransactions(companyDatabase()).read(work);
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
                -- The posted filter sits on the entry join itself. On a LEFT
                -- JOIN of voucher it would only null v's columns while SUM still
                -- read every entry, drafts included - the P6 3.1 defect class.
                LEFT JOIN voucher_entry e ON e.ledger_id = l.id
                     AND EXISTS (SELECT 1 FROM voucher v
                                 WHERE v.id = e.voucher_id AND v.is_posted = 1)
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
