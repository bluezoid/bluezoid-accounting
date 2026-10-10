package com.accubook.accounting.posting;

import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleState;
import com.accubook.persistence.connection.Database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** A real company database with the minimum masters a posting needs. */
class PostingTestFixture implements AutoCloseable {

    static final ModuleId SALES = ModuleId.of("sales-invoicing");
    static final String FY = "fy1";
    static final String VOUCHER_TYPE = "vt1";
    static final String CASH = "l1";
    static final String SALES_LEDGER = "l2";

    private final Database database;
    private final Path backups;
    private final Map<String, ModuleState> states = new HashMap<>();

    PostingTestFixture(Path directory) throws SQLException {
        this.database = Database.open(directory.resolve("company.db"));
        this.backups = directory.resolve("backups");
        applySchema();
        seedMasters();
        states.put(SALES.value(), ModuleState.ACTIVE);
    }

    /**
     * The shipped migrations, applied by the real runner - not a copy of its
     * statement splitter. A copy here once treated the {@code END} of a
     * {@code CASE} inside a trigger as the end of the trigger.
     */
    private void applySchema() throws SQLException {
        new com.accubook.persistence.migration.MigrationRunner(
                com.accubook.app.schema.Schemas.companyDatabase(), backups).migrate(database);
    }

    private void seedMasters() throws SQLException {
        database.inUnguardedTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO company_info (id, name, created_at, updated_at) "
                        + "VALUES ('c1', 'Test Co', 0, 0)");
                s.execute("INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                        + "VALUES ('fy1', 'FY 2026-27', '2026-04-01', '2027-03-31', 0)");
                s.execute("INSERT INTO ledger_group (id, name, nature, created_at, updated_at) "
                        + "VALUES ('g1', 'Assets', 'ASSETS', 0, 0)");
                s.execute("INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                        + "VALUES ('l1', 'g1', 'Cash', 0, 0)");
                s.execute("INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                        + "VALUES ('l2', 'g1', 'Sales', 0, 0)");
                s.execute("INSERT INTO voucher_type (id, code, name, nature, created_at, updated_at) "
                        + "VALUES ('vt1', 'JOURNAL', 'Journal', 'JOURNAL', 0, 0)");
            }
            return null;
        });
    }

    public Database database() {
        return database;
    }

    void setState(ModuleId module, ModuleState state) {
        states.put(module.value(), state);
    }

    PostingService service() {
        EntitlementGuard guard = new EntitlementGuard(
                id -> states.getOrDefault(id, ModuleState.ABSENT));
        return new PostingService(database, guard);
    }

    long count(String table) throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    String scalar(String sql) throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    @Override
    public void close() throws SQLException {
        database.close();
    }
}
