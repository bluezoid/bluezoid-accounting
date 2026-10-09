package com.accubook.app.schema;

import com.accubook.persistence.connection.Database;
import com.accubook.persistence.migration.Migration;
import com.accubook.persistence.migration.MigrationException;
import com.accubook.persistence.migration.MigrationResult;
import com.accubook.persistence.migration.MigrationRunner;
import com.accubook.persistence.migration.SchemaNewerThanApplicationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration safety against the real accounting schema, with real data in it.
 *
 * <p>The governing rule for the whole product: an application update must never
 * delete, overwrite or corrupt customer accounting data. Each test below is one
 * way an update could break that promise.
 */
@DisplayName("Schema migration safety")
class SchemaMigrationSafetyIT {

    /**
     * The shipped schema's version. The synthetic migrations below sit just
     * past it, so a real new migration never collides with them.
     */
    private static final int LATEST = Schemas.companyDatabase().size();

    /** A plausible future migration: additive, the common case. */
    private static final Migration V2_ADD_COLUMN = new Migration(LATEST + 1, "add ledger description", """
            ALTER TABLE ledger ADD COLUMN description TEXT;
            """);

    /** A migration that fails partway, to prove recovery. */
    private static final Migration V3_BROKEN = new Migration(LATEST + 2, "deliberately invalid", """
            ALTER TABLE ledger ADD COLUMN ok_column TEXT;
            THIS IS NOT VALID SQL;
            """);

    @Test
    @DisplayName("the core schema applies to a fresh database")
    void coreSchemaApplies(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("company.db"))) {
            MigrationResult result = runner(dir, Schemas.companyDatabase()).migrate(database);

            assertThat(result.changed()).isTrue();
            // Migration 2 (P5) posting invariants; migration 3 (P6) account
            // governance.
            assertThat(result.toVersion()).isEqualTo(3);
            assertThat(tables(database)).contains(
                    "company_info", "fiscal_year", "ledger_group", "ledger",
                    "voucher_type", "number_counter", "voucher", "voucher_entry", "audit_log");
        }
    }

    @Test
    @DisplayName("customer data survives an additive migration")
    void dataSurvivesMigration(@TempDir Path dir) throws SQLException {
        Path file = dir.resolve("company.db");
        seedCompanyWithPostings(dir, file);

        try (Database database = Database.open(file)) {
            List<Migration> withV2 = new ArrayList<>(Schemas.companyDatabase());
            withV2.add(V2_ADD_COLUMN);
            MigrationResult result = runner(dir, withV2).migrate(database);

            assertThat(result.toVersion()).isEqualTo(LATEST + 1);
            assertThat(result.backup()).as("a populated database is backed up first").isPresent();
            assertThat(count(database, "voucher_entry")).isEqualTo(2);
            assertThat(count(database, "ledger_group")).isPositive();
            assertThat(readCompanyName(database)).isEqualTo("Alpha Traders");
        }
    }

    @Test
    @DisplayName("the books still balance after a migration")
    void trialBalanceSurvivesMigration(@TempDir Path dir) throws SQLException {
        Path file = dir.resolve("company.db");
        seedCompanyWithPostings(dir, file);

        try (Database database = Database.open(file)) {
            List<Migration> withV2 = new ArrayList<>(Schemas.companyDatabase());
            withV2.add(V2_ADD_COLUMN);
            runner(dir, withV2).migrate(database);

            // A migration can be structurally valid and still have corrupted the
            // books. Only an accounting assertion catches that.
            try (var connection = database.openReadConnection();
                 Statement statement = connection.createStatement();
                 var rs = statement.executeQuery(
                         "SELECT COALESCE(SUM(debit_amount_e4), 0), COALESCE(SUM(credit_amount_e4), 0) "
                                 + "FROM voucher_entry")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(rs.getLong(2)).isEqualTo(50_000L);
            }
        }
    }

    @Test
    @DisplayName("a failed migration restores the data and keeps the failed copy")
    void failedMigrationRestoresData(@TempDir Path dir) throws SQLException {
        Path file = dir.resolve("company.db");
        seedCompanyWithPostings(dir, file);

        try (Database database = Database.open(file)) {
            List<Migration> broken = new ArrayList<>(Schemas.companyDatabase());
            broken.add(V2_ADD_COLUMN);
            broken.add(V3_BROKEN);
            assertThatThrownBy(() -> runner(dir, broken).migrate(database))
                    .isInstanceOf(MigrationException.class);
        }

        // The point of the whole exercise: the customer still has their books.
        try (Database database = Database.open(file)) {
            assertThat(count(database, "voucher_entry")).isEqualTo(2);
            assertThat(readCompanyName(database)).isEqualTo("Alpha Traders");
        }
    }

    @Test
    @DisplayName("a database from a newer version is refused, unmodified")
    void newerSchemaRefused(@TempDir Path dir) throws SQLException {
        Path file = dir.resolve("company.db");
        seedCompanyWithPostings(dir, file);

        // Migrate forward with a build that knows about v2.
        try (Database database = Database.open(file)) {
            List<Migration> withV2 = new ArrayList<>(Schemas.companyDatabase());
            withV2.add(V2_ADD_COLUMN);
            runner(dir, withV2).migrate(database);
        }

        // An older build must decline: it does not know about newer columns or
        // constraints, so it would write rows that quietly violate them.
        try (Database database = Database.open(file)) {
            assertThatThrownBy(() -> runner(dir, Schemas.companyDatabase()).migrate(database))
                    .isInstanceOf(SchemaNewerThanApplicationException.class)
                    .hasMessageContaining("newer version");
        }

        try (Database database = Database.open(file)) {
            assertThat(count(database, "voucher_entry"))
                    .as("refusing must not modify the file").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("company identity survives migration")
    void identitySurvivesMigration(@TempDir Path dir) throws SQLException {
        Path file = dir.resolve("company.db");
        seedCompanyWithPostings(dir, file);

        try (Database database = Database.open(file)) {
            List<Migration> withV2 = new ArrayList<>(Schemas.companyDatabase());
            withV2.add(V2_ADD_COLUMN);
            runner(dir, withV2).migrate(database);
            assertThat(readCompanyName(database)).isEqualTo("Alpha Traders");
        }
    }

    @Test
    @DisplayName("the pre-migration backup is a usable database")
    void backupIsUsable(@TempDir Path dir) throws SQLException {
        Path file = dir.resolve("company.db");
        seedCompanyWithPostings(dir, file);

        Path backup;
        try (Database database = Database.open(file)) {
            List<Migration> withV2 = new ArrayList<>(Schemas.companyDatabase());
            withV2.add(V2_ADD_COLUMN);
            backup = runner(dir, withV2).migrate(database).backup().orElseThrow();
        }

        assertThat(Files.exists(backup)).isTrue();
        try (Database restored = Database.open(backup)) {
            assertThat(count(restored, "voucher_entry")).isEqualTo(2);
            assertThat(readCompanyName(restored)).isEqualTo("Alpha Traders");
        }
    }

    @Test
    @DisplayName("re-running a completed migration changes nothing")
    void migrationIsIdempotent(@TempDir Path dir) throws SQLException {
        Path file = dir.resolve("company.db");
        seedCompanyWithPostings(dir, file);

        try (Database database = Database.open(file)) {
            MigrationResult second = runner(dir, Schemas.companyDatabase()).migrate(database);
            assertThat(second.changed()).isFalse();
            assertThat(count(database, "voucher_entry")).isEqualTo(2);
        }
    }

    private MigrationRunner runner(Path dir, List<Migration> migrations) {
        return new MigrationRunner(migrations, dir.resolve("backups"));
    }

    /** A company with a balanced two-line journal entry. */
    private void seedCompanyWithPostings(Path dir, Path file) throws SQLException {
        try (Database database = Database.open(file)) {
            runner(dir, Schemas.companyDatabase()).migrate(database);
            database.inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    s.execute("INSERT INTO company_info (id, name, created_at, updated_at) "
                            + "VALUES ('c1', 'Alpha Traders', 0, 0)");
                    s.execute("INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                            + "VALUES ('fy1', 'FY 2026-27', '2026-04-01', '2027-03-31', 0)");
                    s.execute("INSERT INTO ledger_group (id, name, nature, created_at, updated_at) "
                            + "VALUES ('g1', 'Assets', 'ASSETS', 0, 0)");
                    s.execute("INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                            + "VALUES ('l1', 'g1', 'Cash', 0, 0)");
                    s.execute("INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                            + "VALUES ('l2', 'g1', 'Bank', 0, 0)");
                    s.execute("INSERT INTO voucher_type (id, code, name, nature, created_at, updated_at) "
                            + "VALUES ('vt1', 'JOURNAL', 'Journal', 'JOURNAL', 0, 0)");
                    s.execute("INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, "
                            + "voucher_date, total_debit_e4, total_credit_e4, status, is_posted, "
                            + "posted_at, created_at, updated_at) "
                            + "VALUES ('v1', 'fy1', 'vt1', 'JV-1', '2026-04-01', 50000, 50000, "
                            + "'POSTED', 1, 1, 0, 0)");
                    s.execute("INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                            + "credit_amount_e4, created_at) VALUES ('e1', 'v1', 'l1', 50000, 0, 0)");
                    s.execute("INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                            + "credit_amount_e4, created_at) VALUES ('e2', 'v1', 'l2', 0, 50000, 0)");
                }
                return null;
            });
        }
    }

    private List<String> tables(Database database) throws SQLException {
        List<String> names = new ArrayList<>();
        try (var connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    private int count(Database database, String table) throws SQLException {
        try (var connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private String readCompanyName(Database database) throws SQLException {
        try (var connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT name FROM company_info")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
