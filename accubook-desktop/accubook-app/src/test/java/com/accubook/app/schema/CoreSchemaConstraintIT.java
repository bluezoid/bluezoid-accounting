package com.accubook.app.schema;

import com.accubook.persistence.connection.Database;
import com.accubook.persistence.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema's constraints, asserted as behaviour.
 *
 * <p>These matter because the reference application declares no enums and no
 * CHECK constraints at all, and paid for it: a status column that write paths
 * filled with {@code DEBIT}/{@code CREDIT} while read paths compared against
 * {@code DR}/{@code CR}, and an attendance column written as {@code WEEK_OFF}
 * but filtered as {@code WEEKEND}. Both were invisible until the numbers were
 * wrong. A constraint turns that class of defect into an immediate failure.
 */
@DisplayName("Core schema constraints")
class CoreSchemaConstraintIT {

    @TempDir
    Path dir;

    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(dir.resolve("company.db"));
        new MigrationRunner(Schemas.companyDatabase(), dir.resolve("backups")).migrate(database);
        seedMinimum();
    }

    @Test
    @DisplayName("rejects an unknown ledger group nature")
    void rejectsUnknownNature() {
        assertThatThrownBy(() -> exec(
                "INSERT INTO ledger_group (id, name, nature, created_at, updated_at) "
                        + "VALUES ('g9', 'Typo', 'ASETS', 0, 0)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("rejects an entry carrying both a debit and a credit")
    void rejectsEntryWithBothSides() {
        assertThatThrownBy(() -> exec(
                "INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                        + "credit_amount_e4, created_at) VALUES ('e9', 'v1', 'l1', 100, 100, 0)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("rejects a negative amount")
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> exec(
                "INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                        + "credit_amount_e4, created_at) VALUES ('e9', 'v1', 'l1', -100, 0, 0)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("rejects an entry with no amount at all")
    void rejectsEmptyEntry() {
        assertThatThrownBy(() -> exec(
                "INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                        + "credit_amount_e4, created_at) VALUES ('e9', 'v1', 'l1', 0, 0, 0)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("rejects an unbalanced voucher header")
    void rejectsUnbalancedVoucher() {
        // Double entry enforced by the database, not only by a service the
        // reference's own Tally importer bypasses.
        assertThatThrownBy(() -> exec(
                "INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, "
                        + "voucher_date, total_debit_e4, total_credit_e4, created_at, updated_at) "
                        + "VALUES ('v9', 'fy1', 'vt1', 'JV-2', '2026-04-02', 1000, 900, 0, 0)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("rejects an opening balance direction outside DR/CR")
    void rejectsUnknownOpeningBalanceType() {
        // Defect D-1: the reference stores DEBIT/CREDIT and reads DR/CR, so
        // every user-entered opening balance is sign-flipped.
        assertThatThrownBy(() -> exec(
                "INSERT INTO ledger (id, group_id, name, opening_balance_e4, "
                        + "opening_balance_type, created_at, updated_at) "
                        + "VALUES ('l9', 'g1', 'Bad', 100, 'DEBIT', 0, 0)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("rejects an opening balance with no direction")
    void rejectsOpeningBalanceWithoutDirection() {
        assertThatThrownBy(() -> exec(
                "INSERT INTO ledger (id, group_id, name, opening_balance_e4, created_at, updated_at) "
                        + "VALUES ('l9', 'g1', 'Bad', 100, 0, 0)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("rejects a second company_info row")
    void rejectsSecondCompanyInfoRow() {
        // Decision P2-4: a company database describes exactly one company.
        assertThatThrownBy(() -> exec(
                "INSERT INTO company_info (id, name, created_at, updated_at) "
                        + "VALUES ('c2', 'Other Co', 0, 0)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("rejects a fiscal year ending before it starts")
    void rejectsInvertedFiscalYear() {
        assertThatThrownBy(() -> exec(
                "INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                        + "VALUES ('fy9', 'Bad', '2026-03-31', '2025-04-01', 0)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("rejects a float in a money column")
    void rejectsFloatInMoneyColumn() {
        // STRICT tables. Without them SQLite applies type affinity and stores a
        // float in a column declared DECIMAL, which is decision B-3's premise.
        assertThatThrownBy(() -> exec(
                "INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                        + "credit_amount_e4, created_at) VALUES ('e9', 'v1', 'l1', 100.5, 0, 0)"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("audit_log rejects UPDATE and DELETE")
    void auditLogIsAppendOnly() throws SQLException {
        exec("INSERT INTO audit_log (id, sequence, occurred_at, action, entity_type, entry_hash) "
                + "VALUES ('a1', 1, 0, 'CREATE', 'Company', 'hash1')");

        assertThatThrownBy(() -> exec("UPDATE audit_log SET action = 'TAMPERED' WHERE id = 'a1'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> exec("DELETE FROM audit_log WHERE id = 'a1'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");

        assertThat(count("audit_log")).isEqualTo(1);
    }

    @Test
    @DisplayName("accepts a well-formed balanced voucher")
    void acceptsValidVoucher() throws SQLException {
        exec("INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, "
                + "voucher_date, total_debit_e4, total_credit_e4, created_at, updated_at) "
                + "VALUES ('v2', 'fy1', 'vt1', 'JV-2', '2026-04-02', 5000, 5000, 0, 0)");
        exec("INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                + "credit_amount_e4, created_at) VALUES ('e1', 'v2', 'l1', 5000, 0, 0)");
        exec("INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                + "credit_amount_e4, created_at) VALUES ('e2', 'v2', 'l2', 0, 5000, 0)");

        assertThat(count("voucher_entry")).isEqualTo(2);
    }

    /** Minimum rows the constraint tests reference. */
    private void seedMinimum() throws SQLException {
        exec("INSERT INTO company_info (id, name, created_at, updated_at) VALUES ('c1', 'Test Co', 0, 0)");
        exec("INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                + "VALUES ('fy1', 'FY 2026-27', '2026-04-01', '2027-03-31', 0)");
        exec("INSERT INTO ledger_group (id, name, nature, created_at, updated_at) "
                + "VALUES ('g1', 'Assets', 'ASSETS', 0, 0)");
        exec("INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                + "VALUES ('l1', 'g1', 'Cash', 0, 0)");
        exec("INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                + "VALUES ('l2', 'g1', 'Bank', 0, 0)");
        exec("INSERT INTO voucher_type (id, code, name, nature, created_at, updated_at) "
                + "VALUES ('vt1', 'JOURNAL', 'Journal', 'JOURNAL', 0, 0)");
        // A non-zero voucher. Migration 2 refuses total_debit_e4 = 0: a
        // zero-total voucher satisfies debit == credit yet moves nothing, which
        // is how an entry-less voucher used to pass every constraint (A-1).
        exec("INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, "
                + "voucher_date, total_debit_e4, total_credit_e4, created_at, updated_at) "
                + "VALUES ('v1', 'fy1', 'vt1', 'JV-1', '2026-04-01', 1000000, 1000000, 0, 0)");
    }

    private void exec(String sql) throws SQLException {
        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            return null;
        });
    }

    private int count(String table) throws SQLException {
        try (var connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
