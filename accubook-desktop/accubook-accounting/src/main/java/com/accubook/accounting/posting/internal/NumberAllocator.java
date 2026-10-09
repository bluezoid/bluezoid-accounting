package com.accubook.accounting.posting.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Allocates the next voucher number for a scope.
 *
 * <p>Runs <strong>inside</strong> the posting transaction, which is what makes
 * it safe. The transaction is opened with {@code BEGIN IMMEDIATE}, so the write
 * lock is already held and two concurrent posts queue rather than race. P2
 * measured the alternative: a deferred transaction that reads first and upgrades
 * its lock later fails with {@code SQLITE_BUSY} immediately, regardless of
 * {@code busy_timeout}.
 *
 * <p>A rollback leaves a gap in the sequence. That is accepted: gaps are
 * explicable, duplicates are not.
 */
final class NumberAllocator {

    private NumberAllocator() {
    }

    /**
     * @param scope typically voucher type + fiscal year
     * @param now   timestamp for the counter row
     * @return the next number in that scope, already reserved
     */
    static long next(Connection connection, String scope, long now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO number_counter (scope, last_number, updated_at) VALUES (?, 0, ?) "
                        + "ON CONFLICT (scope) DO NOTHING")) {
            insert.setString(1, scope);
            insert.setLong(2, now);
            insert.executeUpdate();
        }
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE number_counter SET last_number = last_number + 1, updated_at = ? "
                        + "WHERE scope = ?")) {
            update.setLong(1, now);
            update.setString(2, scope);
            update.executeUpdate();
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT last_number FROM number_counter WHERE scope = ?")) {
            select.setString(1, scope);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("Counter vanished for scope " + scope);
                }
                return rows.getLong(1);
            }
        }
    }
}
