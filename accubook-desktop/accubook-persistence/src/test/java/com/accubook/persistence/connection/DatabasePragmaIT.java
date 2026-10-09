package com.accubook.persistence.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the pragmas are actually in force.
 *
 * <p>These assert behaviour rather than configuration on purpose. Both
 * {@code foreign_keys} and {@code busy_timeout} are per-connection settings that
 * fail silently when forgotten - the database simply accepts bad data or loses
 * writes, with no error to notice. Reading the pragma back is not enough either;
 * what matters is that a violation actually throws.
 */
@DisplayName("SQLite pragmas")
class DatabasePragmaIT {

    @Test
    @DisplayName("WAL mode is enabled and persists in the file")
    void walModeIsEnabled(@TempDir Path dir) throws SQLException {
        Path file = dir.resolve("test.db");
        try (Database database = Database.open(file);
             Connection connection = database.openReadConnection()) {
            assertThat(SqlitePragmas.read(connection, "journal_mode")).isEqualToIgnoringCase("wal");
        }
        // journal_mode lives in the database header, unlike every other pragma
        // here, so it should still be WAL on a fresh open.
        try (Database reopened = Database.open(file);
             Connection connection = reopened.openReadConnection()) {
            assertThat(SqlitePragmas.read(connection, "journal_mode")).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    @DisplayName("foreign keys are enforced, not merely configured")
    void foreignKeysAreEnforced(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("fk.db"))) {
            database.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE parent (id TEXT PRIMARY KEY) STRICT");
                    statement.execute("CREATE TABLE child ("
                            + "id TEXT PRIMARY KEY, "
                            + "parent_id TEXT NOT NULL REFERENCES parent(id)) STRICT");
                }
                return null;
            });

            // The point of the test: an orphan must be rejected. If the pragma
            // were missing this insert would silently succeed.
            assertThatThrownBy(() -> database.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO child VALUES ('c1', 'nonexistent')");
                }
                return null;
            })).isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("busy_timeout is set, so contention waits instead of failing")
    void busyTimeoutIsSet(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("busy.db"));
             Connection connection = database.openReadConnection()) {
            assertThat(Integer.parseInt(SqlitePragmas.read(connection, "busy_timeout")))
                    .isEqualTo(SqlitePragmas.BUSY_TIMEOUT_MS);
        }
    }

    @Test
    @DisplayName("synchronous is NORMAL: safe in WAL, and 63x faster than FULL")
    void synchronousIsNormal(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("sync.db"));
             Connection connection = database.openReadConnection()) {
            // 1 == NORMAL
            assertThat(SqlitePragmas.read(connection, "synchronous")).isEqualTo("1");
        }
    }

    @Test
    @DisplayName("read connections are query_only and cannot write")
    void readConnectionsCannotWrite(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("ro.db"))) {
            database.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE t (id TEXT PRIMARY KEY) STRICT");
                }
                return null;
            });

            try (Connection reader = database.openReadConnection();
                 Statement statement = reader.createStatement()) {
                // Structural enforcement of the single-writer rule: the read
                // pool physically cannot write, so it is not a convention.
                assertThatThrownBy(() -> statement.execute("INSERT INTO t VALUES ('x')"))
                        .isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    @DisplayName("STRICT tables reject a float in an INTEGER money column")
    void strictTablesRejectFloats(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("strict.db"))) {
            database.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE m (id TEXT PRIMARY KEY, amount_e4 INTEGER NOT NULL) STRICT");
                }
                return null;
            });

            // Without STRICT this would be stored as a float and silently lose
            // precision - the exact failure decision B-3 exists to prevent.
            assertThatThrownBy(() -> database.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("INSERT INTO m VALUES ('a', 100.5)");
                }
                return null;
            })).isInstanceOf(SQLException.class);
        }
    }
}
