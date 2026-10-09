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
    private final Map<String, ModuleState> states = new HashMap<>();

    PostingTestFixture(Path directory) throws SQLException {
        this.database = Database.open(directory.resolve("company.db"));
        applySchema();
        seedMasters();
        states.put(SALES.value(), ModuleState.ACTIVE);
    }

    private void applySchema() throws SQLException {
        for (var migration : com.accubook.app.schema.Schemas.companyDatabase()) {
            database.inUnguardedTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : splitStatements(migration.sql())) {
                        statement.execute(sql);
                    }
                }
                return null;
            });
        }
    }

    /** Mirrors the migration runner's trigger-aware splitting. */
    private static java.util.List<String> splitStatements(String sql) {
        java.util.List<String> statements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        int blockDepth = 0;
        for (String line : sql.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith("BEGIN") && !upper.startsWith("BEGIN IMMEDIATE")) {
                blockDepth++;
            }
            if (upper.startsWith("END;") || upper.equals("END")) {
                blockDepth = Math.max(0, blockDepth - 1);
                if (blockDepth == 0) {
                    statements.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            if (blockDepth == 0 && trimmed.endsWith(";")) {
                statements.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
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
