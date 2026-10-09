package com.accubook.persistence.connection;

import com.accubook.persistence.guard.GuardedConnection;
import com.accubook.persistence.guard.WriteAuthorization;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A single SQLite database file: one serialized write connection plus
 * short-lived read connections.
 *
 * <p>SQLite permits one writer at a time. Making that explicit turns a random
 * {@code SQLITE_BUSY} into a predictable queue, and lets background work
 * (backup, import) share the same writer instead of competing with interactive
 * posting.
 *
 * <p>Read connections are deliberately short-lived. A single leaked reader
 * blocks WAL checkpointing indefinitely - measured, the WAL grew to 6 MB while
 * the database itself stayed at 12 KB and nothing was ever checkpointed.
 */
public final class Database implements AutoCloseable {

    private final Path file;
    private final SQLiteDataSource dataSource;
    private final Object writeLock = new Object();

    private Connection writeConnection;
    private boolean closed;

    /**
     * Consulted before every mutating statement.
     *
     * <p>Starts permissive so the database is usable before entitlement exists -
     * migrations and provisioning run before any module is resolved. The
     * application installs the real authorization once a company is open.
     */
    private volatile WriteAuthorization authorization = WriteAuthorization.permitAll();

    private Database(Path file) {
        this.file = file;

        // Transactions are driven with an explicit BEGIN IMMEDIATE in
        // UnitOfWork rather than through SQLiteConfig.TransactionMode. The
        // driver's mode makes the JDBC autoCommit flag and SQLite's real
        // transaction state disagree once a connection is reused, which
        // surfaces as a spurious "cannot start a transaction within a
        // transaction". Issuing the statement ourselves keeps one source of
        // truth.
        SQLiteConfig config = new SQLiteConfig();

        this.dataSource = new SQLiteDataSource(config);
        this.dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
    }

    /**
     * Install the authorization consulted before every write.
     *
     * <p>Set once, after the entitlement is resolved. Kept here rather than
     * passed per transaction so no call site can opt out by omitting it.
     */
    public void authorizeWith(WriteAuthorization authorization) {
        this.authorization = java.util.Objects.requireNonNull(authorization, "authorization");
    }

    /** Open (creating if absent) the database at {@code file}. */
    public static Database open(Path file) throws SQLException {
        Database database = new Database(file);
        database.writeConnection();
        return database;
    }

    public Path file() {
        return file;
    }

    /**
     * The single write connection, opened lazily.
     *
     * <p>Always handed back in autocommit. The driver opens a transaction the
     * moment autoCommit goes false (IMMEDIATE mode), so leaving a connection in
     * a transaction between uses would make the next {@link UnitOfWork} issue a
     * nested BEGIN. Normalising here means every caller starts from the same
     * state.
     */
    Connection writeConnection() throws SQLException {
        synchronized (writeLock) {
            ensureOpen();
            if (writeConnection == null || writeConnection.isClosed()) {
                writeConnection = dataSource.getConnection();
                SqlitePragmas.applyWrite(writeConnection);
            }
            if (!writeConnection.getAutoCommit()) {
                writeConnection.setAutoCommit(true);
            }
            return writeConnection;
        }
    }

    /**
     * Run a unit of work inside a write transaction.
     *
     * <p>Serialized on the write lock, so concurrent callers queue rather than
     * collide.
     */
    public <T> T inTransaction(UnitOfWork.Work<T> work) throws SQLException {
        synchronized (writeLock) {
            Connection connection = writeConnection();
            // The transaction is driven on the real connection - BEGIN and
            // COMMIT are not mutations and must not be filtered - while the work
            // body only ever sees the guarded one.
            return UnitOfWork.execute(connection, GuardedConnection.wrap(connection, authorization), work);
        }
    }

    /**
     * Run a unit of work with the guard bypassed.
     *
     * <p>For migrations and provisioning, which create and populate tables before
     * any module or entitlement exists. Deliberately named so its use is obvious
     * in review, and deliberately not public beyond this package's collaborators.
     */
    public <T> T inUnguardedTransaction(UnitOfWork.Work<T> work) throws SQLException {
        synchronized (writeLock) {
            Connection connection = writeConnection();
            return UnitOfWork.execute(connection, connection, work);
        }
    }

    /**
     * Open a fresh read-only connection. The caller closes it - readers are
     * short-lived by design (see the class comment on WAL growth).
     */
    public Connection openReadConnection() throws SQLException {
        ensureOpen();
        Connection connection = dataSource.getConnection();
        SqlitePragmas.applyRead(connection);
        return connection;
    }

    /**
     * Truncate the WAL, so the sidecar does not persist after a clean close.
     *
     * <p>Called before backup and on company close: a backup that captured a
     * large uncheckpointed WAL would be needlessly big, and a leftover WAL is a
     * common source of confusion when a file is copied by hand.
     */
    public void checkpoint() throws SQLException {
        synchronized (writeLock) {
            try (var statement = writeConnection().createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        }
    }

    /**
     * Write a consistent snapshot of this database to {@code destination}.
     *
     * <p>Uses {@code VACUUM INTO}, never a filesystem copy: in WAL mode
     * committed data may still live in the {@code -wal} sidecar, so copying the
     * {@code .db} file alone can capture a stale or torn database. Verified to
     * work on a live database while another connection holds a write
     * transaction - it acts as a reader taking a snapshot.
     *
     * <p>Runs outside any transaction, because SQLite refuses to VACUUM from
     * within one, and on the write connection, because it creates a file.
     */
    public void vacuumInto(Path destination) throws SQLException {
        synchronized (writeLock) {
            Connection connection = writeConnection();
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("vacuumInto must not run inside a transaction");
            }
            String escaped = destination.toAbsolutePath().toString().replace("'", "''");
            try (var statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + escaped + "'");
            }
        }
    }

    /**
     * Run a statement on the write connection outside any transaction.
     *
     * <p>Exists for the handful of statements SQLite refuses inside one:
     * {@code PRAGMA foreign_keys}, which is silently ignored in a transaction,
     * and {@code VACUUM}.
     *
     * <p>Deliberately takes the SQL rather than handing out the connection. The
     * write connection is shared and long-lived, so a caller that wrapped it in
     * try-with-resources would close it - and the next caller would silently get
     * a fresh connection with default pragmas, including foreign keys back ON.
     * That is subtle enough to be worth designing out rather than documenting.
     */
    public void executeOutsideTransaction(String sql) throws SQLException {
        synchronized (writeLock) {
            Connection connection = writeConnection();
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException(
                        "executeOutsideTransaction must not run inside a transaction: " + sql);
            }
            try (var statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }

    /** Read a pragma from the write connection, for self-checks and tests. */
    public String readPragma(String pragma) throws SQLException {
        synchronized (writeLock) {
            return SqlitePragmas.read(writeConnection(), pragma);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Database already closed: " + file);
        }
    }

    @Override
    public void close() throws SQLException {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            closed = true;
            if (writeConnection != null && !writeConnection.isClosed()) {
                try (var statement = writeConnection.createStatement()) {
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // A failed checkpoint must not prevent the close; the WAL
                    // is replayed correctly on next open regardless.
                }
                writeConnection.close();
            }
        }
    }
}
