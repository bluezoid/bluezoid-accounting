package com.accubook.persistence.migration;

import com.accubook.persistence.connection.Database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Health checks run on open, before backup, and after migration.
 *
 * <p>Three levels, deliberately, because they cost very different amounts
 * (measured on a 53 MB database):
 *
 * <ul>
 *   <li>{@code quick_check} - 0.06s, btree structure only. Cheap enough for
 *       every open.</li>
 *   <li>{@code integrity_check} - 0.90s, adds index-vs-table comparison. For
 *       backups and post-migration.</li>
 *   <li>{@code foreign_key_check} - 0.02s, orphaned rows.</li>
 * </ul>
 *
 * <p>The fourth check is the one a general-purpose database library would not
 * have: {@link #requireBooksBalanced}. A migration can leave a structurally
 * perfect database whose accounts no longer balance, and only an accounting
 * assertion catches that.
 */
public final class IntegrityChecks {

    private IntegrityChecks() {
    }

    /** Cheap structural check, suitable for every company open. */
    public static void requireQuickCheckPasses(Database database) throws SQLException {
        String result = scalar(database, "PRAGMA quick_check");
        if (!"ok".equalsIgnoreCase(result)) {
            throw new MigrationException(0, "Database failed quick_check: " + result);
        }
    }

    /** Full structural check, for backups and after migration. */
    public static void requireIntact(Database database) throws SQLException {
        String result = scalar(database, "PRAGMA integrity_check");
        if (!"ok".equalsIgnoreCase(result)) {
            throw new MigrationException(0, "Database failed integrity_check: " + result);
        }
    }

    /**
     * Assert no orphaned foreign-key rows.
     *
     * <p>Worth checking explicitly because {@code foreign_keys} is OFF by
     * default and per-connection: a single connection that skipped the pragma
     * could have written orphans that nothing else would notice.
     */
    public static void requireForeignKeysIntact(Database database) throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rs.next()) {
                throw new MigrationException(0,
                        "Foreign key violation in table '" + rs.getString(1) + "' (rowid "
                                + rs.getObject(2) + ") referencing '" + rs.getString(3) + "'");
            }
        }
    }

    /**
     * Assert that total debits equal total credits.
     *
     * <p>The accounting invariant. Structural checks confirm the file is a valid
     * database; this confirms it is still a valid <em>set of books</em>. A
     * mis-scaled money column or a dropped entry passes every other check and
     * fails this one.
     *
     * <p>No-op when the ledger table is absent, so it is safe to call during
     * Phase 1 and on {@code app.db}, which holds no accounting data.
     */
    public static void requireBooksBalanced(Database database) throws SQLException {
        if (!tableExists(database, "voucher_entry")) {
            return;
        }
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COALESCE(SUM(debit_amount_e4), 0), COALESCE(SUM(credit_amount_e4), 0) "
                             + "FROM voucher_entry")) {
            if (rs.next()) {
                long debits = rs.getLong(1);
                long credits = rs.getLong(2);
                if (debits != credits) {
                    throw new MigrationException(0,
                            "Books do not balance: debits " + debits
                                    + " != credits " + credits + " (in minor units)");
                }
            }
        }
    }

    static boolean tableExists(Database database, String table) throws SQLException {
        try (Connection connection = database.openReadConnection();
             var ps = connection.prepareStatement(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String scalar(Database database, String sql) throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
