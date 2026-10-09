package com.accubook.persistence.guard;

import com.accubook.persistence.connection.Database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write guard against a real database.
 *
 * <p>These are the tests that matter for enforcement. The guard exists because
 * the layer above it depends on developer discipline, and the reference
 * application demonstrates where that leads - 55 of 71 mutating routes omitted
 * their permission check. So each test here attacks the guard rather than
 * demonstrating it: the question is not "does it refuse a write" but "can a
 * determined caller get around it".
 */
class WriteGuardTest {

    @TempDir
    Path directory;

    private Database database;

    /** Refuses one table, permits everything else. */
    private static final WriteAuthorization PAYROLL_LOCKED = table ->
            Set.of("payroll_runs").contains(table)
                    ? Optional.of("the 'payroll' module is READ_ONLY")
                    : Optional.empty();

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(directory.resolve("test.db"));
        database.inUnguardedTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE payroll_runs (id INTEGER PRIMARY KEY, amount INTEGER)");
                statement.execute("CREATE TABLE invoices (id INTEGER PRIMARY KEY, amount INTEGER)");
                statement.execute("INSERT INTO payroll_runs VALUES (1, 5000)");
            }
            return null;
        });
        database.authorizeWith(PAYROLL_LOCKED);
    }

    @AfterEach
    void tearDown() throws SQLException {
        database.close();
    }

    private long countIn(String table) throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Nested
    @DisplayName("refusing writes to a locked module")
    class Refusing {

        @Test
        @DisplayName("a prepared INSERT is refused")
        void refusesPreparedInsert() {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (PreparedStatement s =
                                     connection.prepareStatement("INSERT INTO payroll_runs VALUES (2, 100)")) {
                            return s.executeUpdate();
                        }
                    }));
        }

        @Test
        @DisplayName("a plain-statement INSERT is refused - the SQL is only known at execution")
        void refusesStatementInsert() {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate("INSERT INTO payroll_runs VALUES (2, 100)");
                        }
                    }));
        }

        @Test
        @DisplayName("UPDATE and DELETE are refused, not only INSERT")
        void refusesUpdateAndDelete() {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate("UPDATE payroll_runs SET amount = 0");
                        }
                    }));
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate("DELETE FROM payroll_runs");
                        }
                    }));
        }

        @Test
        @DisplayName("a refused write leaves the data untouched")
        void refusedWriteChangesNothing() throws SQLException {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate("DELETE FROM payroll_runs");
                        }
                    }));

            assertEquals(1, countIn("payroll_runs"), "the refused DELETE must not have taken effect");
        }

        @Test
        @DisplayName("a batch containing a refused statement is refused")
        void refusesBatch() {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            s.addBatch("INSERT INTO invoices VALUES (1, 10)");
                            s.addBatch("INSERT INTO payroll_runs VALUES (9, 10)");
                            return s.executeBatch().length;
                        }
                    }));
        }
    }

    @Nested
    @DisplayName("permitting what must stay permitted")
    class Permitting {

        @Test
        @DisplayName("an unlocked module writes normally")
        void permitsUnlockedTable() throws SQLException {
            database.inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("INSERT INTO invoices VALUES (1, 250)");
                }
            });

            assertEquals(1, countIn("invoices"));
        }

        @Test
        @DisplayName("reading a locked module's data is always allowed - history is never withheld")
        void permitsReadingLockedTable() throws SQLException {
            long rows = database.inTransaction(connection -> {
                try (Statement s = connection.createStatement();
                     ResultSet result = s.executeQuery("SELECT COUNT(*) FROM payroll_runs")) {
                    result.next();
                    return result.getLong(1);
                }
            });

            assertEquals(1, rows, "an expired or downgraded customer must still see their own records");
        }

        @Test
        @DisplayName("a transaction still rolls back correctly through the guard")
        void rollbackStillWorks() throws SQLException {
            assertThrows(SQLException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            s.executeUpdate("INSERT INTO invoices VALUES (1, 250)");
                            s.executeUpdate("INSERT INTO invoices VALUES (1, 250)"); // duplicate key
                        }
                        return null;
                    }));

            // SQLite does not poison a transaction on constraint violation, so a
            // guard that interfered with ROLLBACK would leave the first row
            // committed. It must not.
            assertEquals(0, countIn("invoices"));
        }
    }

    @Nested
    @DisplayName("bypass attempts")
    class BypassAttempts {

        @Test
        @DisplayName("unwrapping the connection back to a bare one is refused")
        void refusesUnwrap() {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> connection.unwrap(Connection.class)));
        }

        @Test
        @DisplayName("unwrapping a statement is refused")
        void refusesStatementUnwrap() {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.unwrap(Statement.class);
                        }
                    }));
        }

        @Test
        @DisplayName("a statement whose table cannot be parsed is refused, not waved through")
        void failsClosedOnUnparseableStatement() {
            // The guard must fail closed. A mutating statement it does not
            // understand is exactly the case an attacker would construct, so
            // permitting it would make the whole layer advisory.
            WriteAuthorization refuseEverything = table -> Optional.of("locked");
            database.authorizeWith(refuseEverything);

            UnauthorizedWriteException e = assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate("INSERT /*sneaky*/ payroll_runs VALUES (2, 1)");
                        }
                    }));

            assertTrue(e.getMessage().contains("could not identify the table"), e.getMessage());
        }

        @Test
        @DisplayName("a comment in front of the verb does not hide a write to a locked table")
        void commentDoesNotHideWrite() throws SQLException {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate("/* routine */ INSERT INTO payroll_runs VALUES (2, 1)");
                        }
                    }));
            assertEquals(1, countIn("payroll_runs"));
        }

        @Test
        @DisplayName("a write inside a WITH clause is guarded")
        void cteWriteIsGuarded() throws SQLException {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate(
                                    "WITH n AS (SELECT 3 AS id) INSERT INTO payroll_runs SELECT id, 1 FROM n");
                        }
                    }));
            assertEquals(1, countIn("payroll_runs"));
        }

        @Test
        @DisplayName("a locked table cannot be dropped through a guarded transaction")
        void dropIsRefused() throws SQLException {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.execute("DROP TABLE payroll_runs");
                        }
                    }));
            assertEquals(1, countIn("payroll_runs"));
        }

        @Test
        @DisplayName("COMMIT mid-work is refused, so the unit of work stays atomic")
        void midWorkCommitIsRefused() throws SQLException {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            s.executeUpdate("INSERT INTO invoices VALUES (9, 1)");
                            return s.execute("COMMIT");
                        }
                    }));
            assertEquals(0, countIn("invoices"), "the first insert must have rolled back");
        }

        @Test
        @DisplayName("database settings cannot be changed mid-transaction")
        void settingPragmaIsRefused() {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.execute("PRAGMA foreign_keys = OFF");
                        }
                    }));
        }

        @Test
        @DisplayName("a nested prepared statement created mid-transaction is still guarded")
        void guardsStatementsCreatedLater() {
            assertThrows(UnauthorizedWriteException.class, () ->
                    database.inTransaction(connection -> {
                        try (Statement first = connection.createStatement()) {
                            first.executeUpdate("INSERT INTO invoices VALUES (5, 1)");
                        }
                        // A second statement obtained after the first succeeded
                        // must be guarded too - the wrapper is not one-shot.
                        try (PreparedStatement second =
                                     connection.prepareStatement("INSERT INTO payroll_runs VALUES (7, 1)")) {
                            return second.executeUpdate();
                        }
                    }));
        }
    }
}
