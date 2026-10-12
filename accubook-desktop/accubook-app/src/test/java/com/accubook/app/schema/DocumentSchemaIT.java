package com.accubook.app.schema;

import com.accubook.app.company.CompanyService;
import com.accubook.persistence.connection.Database;
import com.accubook.persistence.migration.MigrationRunner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migration 6: document tables, their invariants, and the seeded ledgers. */
@DisplayName("Document schema")
class DocumentSchemaIT {

    private static void exec(Database database, String sql) throws SQLException {
        database.inUnguardedTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    private static String scalar(Database database, String sql) throws SQLException {
        try (var connection = database.openReadConnection();
             Statement s = connection.createStatement();
             var rows = s.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    @Test
    @DisplayName("an existing company gains Sales and Purchases; a clashing user ledger name is not a failure")
    void seedsExistingCompany(@TempDir Path dir) throws SQLException {
        try (Database database = Database.open(dir.resolve("company.db"))) {
            // A company as it stood at schema 5, with a user ledger already called "Sales".
            new MigrationRunner(Schemas.companyDatabase().subList(0, 5), dir.resolve("b1")).migrate(database);
            exec(database, "INSERT INTO ledger_group (id, system_code, name, nature, is_system, created_at, updated_at) "
                    + "VALUES ('g1', 'SALES_ACCOUNTS', 'Sales Accounts', 'INCOME', 1, 0, 0), "
                    + "('g2', 'DIRECT_EXPENSES', 'Direct Expenses', 'EXPENSES', 1, 0, 0)");
            exec(database, "INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                    + "VALUES ('u1', 'g1', 'Sales', 0, 0)");

            new MigrationRunner(Schemas.companyDatabase(), dir.resolve("b2")).migrate(database);

            assertThat(scalar(database, "SELECT name FROM ledger WHERE system_code = 'SALES'"))
                    .isEqualTo("Sales (AccuBook)");
            assertThat(scalar(database, "SELECT group_id FROM ledger WHERE system_code = 'SALES'")).isEqualTo("g1");
            assertThat(scalar(database, "SELECT name FROM ledger WHERE system_code = 'PURCHASES'"))
                    .isEqualTo("Purchases");
            assertThat(scalar(database, "SELECT is_system FROM ledger WHERE system_code = 'PURCHASES'"))
                    .isEqualTo("1");
            assertThat(scalar(database, "SELECT id FROM ledger WHERE system_code = 'SALES'"))
                    .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        }
    }

    @Test
    @DisplayName("a new company has each seeded ledger exactly once")
    void newCompanySeededOnce(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            String id = service.createCompany("Asha Traders").id();
            try (Database database = Database.open(dir.resolve("companies").resolve(id).resolve("company.db"))) {
                assertThat(scalar(database, "SELECT COUNT(*) FROM ledger WHERE system_code IN ('SALES', 'PURCHASES')"))
                        .isEqualTo("2");
                assertThat(scalar(database, "SELECT g.system_code FROM ledger l JOIN ledger_group g "
                        + "ON g.id = l.group_id WHERE l.system_code = 'PURCHASES'")).isEqualTo("DIRECT_EXPENSES");
            }
        }
    }

    /** A provisioned company with one party and a posted voucher to attach documents to. */
    private static Database company(Path dir) throws Exception {
        String id;
        try (CompanyService service = CompanyService.start(dir)) {
            id = service.createCompany("Asha Traders").id();
        }
        Database database = Database.open(dir.resolve("companies").resolve(id).resolve("company.db"));
        exec(database, "INSERT INTO party (id, ledger_id, role, name, created_at, updated_at) "
                + "SELECT 'p1', id, 'CUSTOMER', 'Cash in Hand', 0, 0 FROM ledger WHERE system_code = 'CASH_IN_HAND'");
        exec(database, "INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, voucher_date, "
                + "total_debit_e4, total_credit_e4, status, is_posted, posted_at, created_at, updated_at) "
                + "SELECT 'v1', (SELECT id FROM fiscal_year), (SELECT id FROM voucher_type WHERE code = 'SALES'), "
                + "'1', '2026-05-01', 1000000, 1000000, 'POSTED', 1, 0, 0, 0");
        return database;
    }

    private static final String DRAFT = "INSERT INTO sales_document (id, kind, party_id, document_date, "
            + "created_at, updated_at) VALUES ('d1', 'SALES_INVOICE', 'p1', '2026-05-01', 0, 0)";

    @Test
    @DisplayName("a draft has no number; a posted document must have a number, a voucher and a positive total")
    void numberingAndPostingInvariants(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            exec(database, DRAFT);
            assertThatThrownBy(() -> exec(database, "UPDATE sales_document SET number = 'INV/26-27/00001' WHERE id = 'd1'"))
                    .hasMessageContaining("CHECK");
            assertThatThrownBy(() -> exec(database, "UPDATE sales_document SET status = 'POSTED', "
                    + "number = 'INV/26-27/00001' WHERE id = 'd1'")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> exec(database, "UPDATE sales_document SET amount_e4 = 1000000, taxable_e4 = 1000000, "
                    + "total_e4 = 999900 "
                    + "WHERE id = 'd1'")).as("total = amount + round off").hasMessageContaining("CHECK");
        }
    }

    @Test
    @DisplayName("P7-11: a credit note must reference an original document; an invoice must not")
    void notesReferenceOriginal(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            exec(database, DRAFT);
            assertThatThrownBy(() -> exec(database, "INSERT INTO sales_document (id, kind, party_id, document_date, "
                    + "created_at, updated_at) VALUES ('c1', 'CREDIT_NOTE', 'p1', '2026-05-02', 0, 0)"))
                    .hasMessageContaining("CHECK");
            assertThatCode(() -> exec(database, "INSERT INTO sales_document (id, kind, party_id, document_date, "
                    + "original_document_id, created_at, updated_at) "
                    + "VALUES ('c2', 'CREDIT_NOTE', 'p1', '2026-05-02', 'd1', 0, 0)")).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("P7-9: a line amount is whole paise")
    void lineAmountWholePaise(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            exec(database, DRAFT);
            assertThatThrownBy(() -> exec(database, "INSERT INTO sales_document_line (id, document_id, sequence, "
                    + "description, quantity_e4, rate_e4, amount_e4) VALUES ('l1', 'd1', 1, 'x', 10000, 3333, 3333)"))
                    .hasMessageContaining("CHECK");
        }
    }

    @Test
    @DisplayName("a posted document and its lines are frozen; only POSTED to CANCELLED is allowed")
    void postedFrozen(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            exec(database, DRAFT);
            exec(database, "INSERT INTO sales_document_line (id, document_id, sequence, description, quantity_e4, "
                    + "rate_e4, amount_e4) VALUES ('l1', 'd1', 1, 'Consulting', 10000, 1000000, 1000000)");
            exec(database, "UPDATE sales_document SET status = 'POSTED', number = 'INV/26-27/00001', "
                    + "amount_e4 = 1000000, taxable_e4 = 1000000, total_e4 = 1000000, voucher_id = 'v1', "
                    + "gst_scheme = 'REGULAR', place_of_supply = '27', supply_type = 'INTRA_STATE', "
                    + "rule_pack_version = '2025.10.1', "
                    + "fiscal_year_id = (SELECT id FROM fiscal_year) WHERE id = 'd1'");

            assertThatThrownBy(() -> exec(database, "UPDATE sales_document SET document_date = '2026-05-02'"))
                    .hasMessageContaining("cannot be changed");
            assertThatThrownBy(() -> exec(database, "DELETE FROM sales_document")).hasMessageContaining("cannot be deleted");
            assertThatThrownBy(() -> exec(database, "UPDATE sales_document_line SET amount_e4 = 0"))
                    .hasMessageContaining("cannot be changed");
            assertThatThrownBy(() -> exec(database, "INSERT INTO sales_document_line (id, document_id, sequence, "
                    + "description, quantity_e4, rate_e4, amount_e4) VALUES ('l2', 'd1', 2, 'x', 10000, 100, 100)"))
                    .hasMessageContaining("cannot be changed");

            assertThatCode(() -> exec(database, "UPDATE sales_document SET status = 'CANCELLED', "
                    + "cancellation_voucher_id = 'v1', cancellation_reason = 'Duplicate' WHERE id = 'd1'"))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> exec(database, "UPDATE sales_document SET status = 'POSTED', "
                    + "cancellation_voucher_id = NULL WHERE id = 'd1'")).hasMessageContaining("cannot be changed");
        }
    }
}
