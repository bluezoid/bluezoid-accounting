package com.accubook.persistence.migration;

import com.accubook.persistence.connection.Database;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The table-rebuild hazard, and the correct procedure.
 *
 * <p>SQLite cannot change a column's type or nullability, or add a foreign key,
 * in place. Those need the twelve-step rebuild: create a new table, copy, drop
 * the old, rename. That is unavoidable, and it is dangerous:
 *
 * <p><strong>With {@code foreign_keys = ON}, dropping the parent table
 * cascade-deletes rows in every child table.</strong> A migration written the
 * obvious way would therefore silently destroy accounting data - the voucher
 * entries would simply be gone, with no error and nothing in the logs.
 *
 * <p>These tests pin both halves: the hazard is real, and the documented
 * procedure avoids it.
 */
@DisplayName("Table rebuild safety")
class TableRebuildSafetyIT {

    @Test
    @DisplayName("HAZARD: dropping a parent with FKs on destroys child rows")
    void droppingParentWithForeignKeysOnDestroysChildren(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("hazard.db"))) {
            createParentAndChild(database);

            // The naive rebuild: drop the parent while FK enforcement is on.
            database.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE parent_new (id TEXT PRIMARY KEY, extra TEXT) STRICT");
                    statement.execute("INSERT INTO parent_new (id) SELECT id FROM parent");
                    statement.execute("DROP TABLE parent");
                    statement.execute("ALTER TABLE parent_new RENAME TO parent");
                }
                return null;
            });

            // This assertion documents the danger rather than a desired
            // behaviour: the child rows are gone.
            assertThat(count(database, "child"))
                    .as("child rows are cascade-deleted - this is why the procedure matters")
                    .isZero();
        }
    }

    @Test
    @DisplayName("CORRECT: disabling FKs first preserves child rows")
    void rebuildWithForeignKeysDisabledPreservesChildren(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("safe.db"))) {
            createParentAndChild(database);

            // PRAGMA foreign_keys is a no-op inside a transaction, so it must be
            // set before BEGIN and restored after COMMIT.
            database.executeOutsideTransaction("PRAGMA foreign_keys = OFF");
            assertThat(database.readPragma("foreign_keys")).isEqualTo("0");

            database.inTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE parent_new (id TEXT PRIMARY KEY, extra TEXT) STRICT");
                    statement.execute("INSERT INTO parent_new (id) SELECT id FROM parent");
                    statement.execute("DROP TABLE parent");
                    statement.execute("ALTER TABLE parent_new RENAME TO parent");
                }
                return null;
            });

            database.executeOutsideTransaction("PRAGMA foreign_keys = ON");

            assertThat(count(database, "child"))
                    .as("child rows survive the rebuild")
                    .isEqualTo(2);
            assertThat(count(database, "parent")).isEqualTo(1);

            // And the relationship is still intact.
            try (var connection = database.openReadConnection();
                 Statement statement = connection.createStatement();
                 var rs = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertThat(rs.next()).as("no orphaned rows after rebuild").isFalse();
            }
        }
    }

    private void createParentAndChild(Database database) throws SQLException {
        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE parent (id TEXT PRIMARY KEY) STRICT");
                statement.execute("CREATE TABLE child ("
                        + "id TEXT PRIMARY KEY, "
                        + "parent_id TEXT NOT NULL REFERENCES parent(id) ON DELETE CASCADE) STRICT");
                statement.execute("INSERT INTO parent VALUES ('p1')");
                statement.execute("INSERT INTO child VALUES ('c1', 'p1')");
                statement.execute("INSERT INTO child VALUES ('c2', 'p1')");
            }
            return null;
        });
    }

    private int count(Database database, String table) throws SQLException {
        try (var connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
