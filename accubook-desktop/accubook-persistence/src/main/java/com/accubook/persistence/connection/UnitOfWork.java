package com.accubook.persistence.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs a body of work inside a SQLite write transaction, correctly.
 *
 * <p>Two behaviours here are architectural rather than stylistic. Both were
 * measured during Phase 0, and both would corrupt accounting data if left to
 * individual call sites.
 *
 * <h2>1. BEGIN IMMEDIATE, not the JDBC default</h2>
 * A <em>deferred</em> transaction that starts by reading and later writes must
 * upgrade its lock, and if another writer holds it that upgrade fails with
 * {@code SQLITE_BUSY} <strong>immediately, regardless of
 * {@code busy_timeout}</strong>. {@code BEGIN IMMEDIATE} takes the write lock up
 * front, so {@code busy_timeout} applies and contention becomes a wait.
 *
 * <p>The statement is issued explicitly rather than via
 * {@code SQLiteConfig.setTransactionMode(IMMEDIATE)}. That configuration option
 * works, but it leaves JDBC's {@code autoCommit} flag and SQLite's real
 * transaction state able to disagree once a connection is reused, which shows up
 * as a spurious "cannot start a transaction within a transaction". Driving the
 * BEGIN here keeps one source of truth.
 *
 * <h2>2. Explicit rollback</h2>
 * PostgreSQL poisons a transaction when a statement violates a constraint:
 * everything after it fails until rollback. <strong>SQLite does not.</strong>
 * It aborts only the offending statement and the transaction stays usable, so a
 * {@code catch}-and-continue would commit the surviving half of a voucher. That
 * is precisely the failure the reference application fought - entries that
 * balanced on paper while only some effects landed. Any {@link SQLException}
 * therefore rolls the whole transaction back.
 */
public final class UnitOfWork {

    /** A body of work executed inside a transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T execute(Connection connection) throws SQLException;
    }

    private UnitOfWork() {
    }

    /**
     * Execute {@code work} inside {@code BEGIN IMMEDIATE} ... {@code COMMIT},
     * rolling back on any failure.
     *
     * @throws SQLException the original failure, after the rollback has run
     */
    public static <T> T execute(Connection connection, Work<T> work) throws SQLException {
        return execute(connection, connection, work);
    }

    /**
     * Execute {@code work} in a transaction driven on {@code control}, handing
     * the body {@code visible}.
     *
     * <p>The split exists so the write guard can wrap what the body sees while
     * BEGIN, COMMIT and ROLLBACK still run on the real connection. Those are
     * transaction control, not mutations, and must never be filtered - a guard
     * that swallowed a ROLLBACK would leave a half-written voucher committed.
     */
    public static <T> T execute(Connection control, Connection visible, Work<T> work) throws SQLException {
        Connection connection = control;
        // autoCommit stays true so the driver does not open a transaction of
        // its own; BEGIN/COMMIT/ROLLBACK are issued as statements here. Units of
        // work must not nest - the inner one would appear to commit while the
        // outer could still roll it back - and SQLite enforces that itself by
        // rejecting a nested BEGIN.
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
        }

        try {
            T result = work.execute(visible);
            try (Statement statement = connection.createStatement()) {
                statement.execute("COMMIT");
            }
            return result;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(connection, e);
            throw e;
        }
    }

    /**
     * Roll back, attaching any rollback failure to the original exception.
     *
     * <p>The original failure is the one worth reporting; a rollback problem is
     * added as a suppressed exception so neither is lost.
     */
    private static void rollbackQuietly(Connection connection, Exception original) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
