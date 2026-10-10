package com.accubook.accounting.chart;

import com.accubook.app.provisioning.CompanyProvisioning;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleState;
import com.accubook.persistence.connection.Database;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** A genuinely provisioned company: real migrations, real seed data. */
final class ChartFixture implements AutoCloseable {

    static final ModuleId ACCOUNTING = ModuleId.of("accounting");

    private final Database database;
    private final Path backups;
    private final Map<String, ModuleState> states = new HashMap<>();

    ChartFixture(Path directory) throws SQLException {
        this.database = Database.open(directory.resolve("company.db"));
        this.backups = directory.resolve("backups");
        applySchema();
        database.inUnguardedTransaction(connection -> {
            CompanyProvisioning.provision(connection, "c1", "Test Co", 4,
                    LocalDate.of(2026, 4, 1));
            return null;
        });
        states.put(ACCOUNTING.value(), ModuleState.ACTIVE);
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

    Database database() {
        return database;
    }

    ChartOfAccounts chart() {
        return new ChartOfAccounts(database);
    }

    EntitlementGuard entitlement() {
        return new EntitlementGuard(id -> states.getOrDefault(id, ModuleState.ABSENT));
    }

    String fiscalYearId() throws SQLException {
        return scalar("SELECT id FROM fiscal_year LIMIT 1");
    }

    String voucherTypeId(String code) throws SQLException {
        return scalar("SELECT id FROM voucher_type WHERE code = '" + code + "'");
    }

    String ledgerIdBySystemCode(String code) throws SQLException {
        return scalar("SELECT id FROM ledger WHERE system_code = '" + code + "'");
    }

    String groupIdBySystemCode(String code) throws SQLException {
        return scalar("SELECT id FROM ledger_group WHERE system_code = '" + code + "'");
    }

    /** Create a plain user account, as the customer would. */
    String createUserLedger(String name, String groupSystemCode) throws SQLException {
        String id = com.accubook.platform.id.Ids.newIdString();
        String groupId = groupIdBySystemCode(groupSystemCode);
        database.inTransaction(connection -> {
            try (var ps = connection.prepareStatement(
                    "INSERT INTO ledger (id, group_id, name, is_system, is_active, "
                            + "created_at, updated_at) VALUES (?, ?, ?, 0, 1, 0, 0)")) {
                ps.setString(1, id);
                ps.setString(2, groupId);
                ps.setString(3, name);
                ps.executeUpdate();
            }
            return null;
        });
        return id;
    }

    long count(String table) throws SQLException {
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
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
