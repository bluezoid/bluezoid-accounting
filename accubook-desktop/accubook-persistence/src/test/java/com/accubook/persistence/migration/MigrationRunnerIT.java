package com.accubook.persistence.migration;

import com.accubook.persistence.connection.Database;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration safety.
 *
 * <p>Governing rule: an application update must never delete, overwrite or
 * corrupt customer accounting data. These tests exist to make that rule
 * falsifiable rather than aspirational - each one describes a way an update
 * could destroy a customer's books, and asserts that it does not.
 */
@DisplayName("MigrationRunner")
class MigrationRunnerIT {

    private static final Migration V1 = new Migration(1, "create ledger", """
            CREATE TABLE ledger (id TEXT PRIMARY KEY, name TEXT NOT NULL) STRICT;
            """);

    private static final Migration V2 = new Migration(2, "add ledger code", """
            ALTER TABLE ledger ADD COLUMN code TEXT;
            """);

    private static final Migration V3_BROKEN = new Migration(3, "deliberately invalid", """
            THIS IS NOT VALID SQL;
            """);

    @Test
    @DisplayName("migrates a fresh database to the latest version")
    void migratesFreshDatabase(@TempDir Path dir) throws SQLException {
        MigrationRunner runner = new MigrationRunner(List.of(V1, V2), dir.resolve("backups"));
        try (Database database = Database.open(dir.resolve("fresh.db"))) {
            MigrationResult result = runner.migrate(database);

            assertThat(result.fromVersion()).isZero();
            assertThat(result.toVersion()).isEqualTo(2);
            assertThat(result.changed()).isTrue();
            // A fresh file has no customer data, so no backup is taken.
            assertThat(result.backup()).isEmpty();
            assertThat(runner.currentVersion(database)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("does nothing when already current")
    void noOpWhenCurrent(@TempDir Path dir) throws SQLException {
        MigrationRunner runner = new MigrationRunner(List.of(V1), dir.resolve("backups"));
        try (Database database = Database.open(dir.resolve("current.db"))) {
            runner.migrate(database);
            MigrationResult second = runner.migrate(database);
            assertThat(second.changed()).isFalse();
        }
    }

    @Test
    @DisplayName("backs up before migrating a database that holds data")
    void backsUpExistingData(@TempDir Path dir) throws SQLException {
        Path backups = dir.resolve("backups");
        Path file = dir.resolve("data.db");

        try (Database database = Database.open(file)) {
            new MigrationRunner(List.of(V1), backups).migrate(database);
            insertLedger(database, "l1", "Cash");
        }

        try (Database database = Database.open(file)) {
            MigrationResult result = new MigrationRunner(List.of(V1, V2), backups).migrate(database);
            assertThat(result.backup()).isPresent();
            assertThat(result.backup().get()).exists();
            assertThat(ledgerCount(database)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("restores the database when a migration fails, losing no data")
    void restoresOnFailure(@TempDir Path dir) throws SQLException {
        Path backups = dir.resolve("backups");
        Path file = dir.resolve("failing.db");

        try (Database database = Database.open(file)) {
            new MigrationRunner(List.of(V1), backups).migrate(database);
            insertLedger(database, "l1", "Cash");
            insertLedger(database, "l2", "Bank");
        }

        try (Database database = Database.open(file)) {
            assertThatThrownBy(() ->
                    new MigrationRunner(List.of(V1, V2, V3_BROKEN), backups).migrate(database))
                    .isInstanceOf(MigrationException.class);
        }

        // The whole point: the customer's rows are still there afterwards.
        try (Database database = Database.open(file)) {
            assertThat(ledgerCount(database)).isEqualTo(2);
            assertThat(new MigrationRunner(List.of(V1), backups).currentVersion(database))
                    .as("schema version rolled back to the last good state")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("refuses a database written by a newer version")
    void refusesNewerSchema(@TempDir Path dir) throws SQLException {
        Path backups = dir.resolve("backups");
        Path file = dir.resolve("newer.db");

        try (Database database = Database.open(file)) {
            new MigrationRunner(List.of(V1, V2), backups).migrate(database);
            insertLedger(database, "l1", "Cash");
        }

        // An older build must not open it: it does not know about newer columns
        // or constraints, so it would write rows that quietly violate them.
        try (Database database = Database.open(file)) {
            assertThatThrownBy(() -> new MigrationRunner(List.of(V1), backups).migrate(database))
                    .isInstanceOf(SchemaNewerThanApplicationException.class)
                    .hasMessageContaining("newer version");
        }

        try (Database database = Database.open(file)) {
            assertThat(ledgerCount(database)).as("refusing must not modify the file").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("applies migrations in version order regardless of list order")
    void appliesInOrder(@TempDir Path dir) throws SQLException {
        MigrationRunner runner = new MigrationRunner(List.of(V2, V1), dir.resolve("backups"));
        try (Database database = Database.open(dir.resolve("ordered.db"))) {
            runner.migrate(database);
            assertThat(runner.currentVersion(database)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("rejects duplicate migration versions at construction")
    void rejectsDuplicateVersions(@TempDir Path dir) {
        assertThatThrownBy(() -> new MigrationRunner(
                List.of(V1, new Migration(1, "clash", "SELECT 1;")), dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate migration version");
    }

    @Test
    @DisplayName("splits a multi-statement script correctly")
    void splitsStatements() {
        List<String> statements = MigrationRunner.splitStatements("""
                -- a comment, ignored
                CREATE TABLE a (id TEXT PRIMARY KEY) STRICT;

                CREATE TABLE b (id TEXT PRIMARY KEY) STRICT;
                """);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("CREATE TABLE a");
        assertThat(statements.get(1)).contains("CREATE TABLE b");
    }

    private void insertLedger(Database database, String id, String name) throws SQLException {
        database.inTransaction(connection -> {
            try (var ps = connection.prepareStatement("INSERT INTO ledger (id, name) VALUES (?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private int ledgerCount(Database database) throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM ledger")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
