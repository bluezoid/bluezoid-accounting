package com.accubook.persistence.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The pragma set every AccuBook connection must carry.
 *
 * <p>Applied centrally rather than by callers: two of these are per-connection
 * settings that silently do nothing if forgotten, and both would corrupt or lose
 * accounting data. Every value here was chosen from a measurement, recorded in
 * {@code docs/architecture/DATABASE_ARCHITECTURE.md}.
 */
public final class SqlitePragmas {

    /**
     * Measured: with {@code busy_timeout = 0}, four concurrent writers lost 900
     * of 1200 writes. Ten seconds is long enough to outlast a checkpoint without
     * making a genuine deadlock feel like a hang.
     */
    public static final int BUSY_TIMEOUT_MS = 10_000;

    /** Bound on WAL growth; not persistent, so it is set per connection. */
    public static final int JOURNAL_SIZE_LIMIT_BYTES = 6 * 1024 * 1024;

    private SqlitePragmas() {
    }

    /**
     * Apply the pragmas a read/write connection needs.
     *
     * <p>{@code foreign_keys} is the one to be careful about: it defaults to OFF
     * and is per-connection, so a connection that skips it silently accepts
     * orphaned rows - voucher entries pointing at deleted ledgers, for instance.
     *
     * <p>{@code synchronous = NORMAL} rather than FULL: measured 63x faster
     * (0.08s vs 5.01s for 2000 commits), and in WAL mode NORMAL cannot corrupt
     * the database. It risks only the last commits on a power loss, which is a
     * reasonable trade that FULL is not.
     */
    public static void applyWrite(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA temp_store = MEMORY");
            statement.execute("PRAGMA journal_size_limit = " + JOURNAL_SIZE_LIMIT_BYTES);
        }
    }

    /**
     * Apply read-only pragmas.
     *
     * <p>{@code query_only} makes the read pool structurally unable to write, so
     * the single-writer rule is enforced by the database rather than by
     * convention.
     */
    public static void applyRead(Connection connection) throws SQLException {
        applyWrite(connection);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = 1");
        }
    }

    /**
     * Read back a pragma, for startup self-checks and tests.
     *
     * <p>Worth asserting rather than assuming: a driver default that differs
     * from the CLI default is exactly the kind of thing that makes local testing
     * disagree with production behaviour.
     */
    public static String read(Connection connection, String pragma) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA " + pragma)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
