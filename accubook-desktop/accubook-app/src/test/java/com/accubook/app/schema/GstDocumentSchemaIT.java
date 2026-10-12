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

/** Migration 11: GST on documents - item tax, the document's GST basis, tax lines and GST ledgers. */
@DisplayName("GST document schema")
class GstDocumentSchemaIT {

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
        exec(database, "INSERT INTO sales_document (id, kind, party_id, document_date, created_at, updated_at) "
                + "VALUES ('d1', 'SALES_INVOICE', 'p1', '2026-05-01', 0, 0)");
        exec(database, "INSERT INTO sales_document_line (id, document_id, sequence, description, quantity_e4, "
                + "rate_e4, amount_e4, tax_category, gst_rate_e4) "
                + "VALUES ('l1', 'd1', 1, 'Consulting', 10000, 1000000, 1000000, 'TAXABLE', 180000)");
        return database;
    }

    private static final String POST = "UPDATE sales_document SET status = 'POSTED', number = 'INV/26-27/00001', "
            + "taxable_e4 = 1000000, tax_e4 = 180000, amount_e4 = 1180000, total_e4 = 1180000, voucher_id = 'v1', "
            + "fiscal_year_id = (SELECT id FROM fiscal_year)";

    private static final String BASIS = ", gst_scheme = 'REGULAR', place_of_supply = '27', "
            + "supply_type = 'INTRA_STATE', rule_pack_version = '2025.10.1'";

    @Test
    @DisplayName("P9-7: a new company has the fifteen GST ledgers once each, under Duties & Taxes")
    void gstLedgersSeeded(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            assertThat(scalar(database, "SELECT COUNT(*) FROM ledger l JOIN ledger_group g ON g.id = l.group_id "
                    + "WHERE l.system_code LIKE 'GST\\_%' ESCAPE '\\' AND g.system_code = 'DUTIES_AND_TAXES' "
                    + "AND l.is_system = 1")).isEqualTo("15");
            assertThat(scalar(database, "SELECT name FROM ledger WHERE system_code = 'GST_RCM_IGST'"))
                    .isEqualTo("RCM Payable IGST");
        }
    }

    @Test
    @DisplayName("an existing company gains the GST ledgers, and its posted documents keep their amounts as taxable value")
    void migratesExistingCompany(@TempDir Path dir) throws Exception {
        try (Database database = Database.open(dir.resolve("company.db"))) {
            new MigrationRunner(Schemas.companyDatabase().subList(0, 10), dir.resolve("b1")).migrate(database);
            exec(database, "INSERT INTO ledger_group (id, system_code, name, nature, is_system, created_at, updated_at) "
                    + "VALUES ('g1', 'DUTIES_AND_TAXES', 'Duties & Taxes', 'LIABILITIES', 1, 0, 0), "
                    + "('g2', 'CAPITAL_ACCOUNT', 'Capital', 'EQUITY', 1, 0, 0)");
            exec(database, "INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                    + "VALUES ('cap', 'g2', 'Capital', 0, 0)");
            exec(database, "INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                    + "VALUES ('fy', '2026-27', '2026-04-01', '2027-03-31', 0)");
            exec(database, "INSERT INTO voucher_type (id, code, name, nature, numbering_prefix, created_at, updated_at) "
                    + "VALUES ('vt', 'SALES', 'Sales', 'SALES', 'SAL', 0, 0)");
            exec(database, "INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, voucher_date, "
                    + "total_debit_e4, total_credit_e4, status, is_posted, posted_at, created_at, updated_at) "
                    + "VALUES ('v1', 'fy', 'vt', '1', '2026-05-01', 1000000, 1000000, 'POSTED', 1, 0, 0, 0)");
            exec(database, "INSERT INTO party (id, ledger_id, role, name, created_at, updated_at) "
                    + "VALUES ('p1', 'cap', 'CUSTOMER', 'Capital', 0, 0)");
            exec(database, "INSERT INTO sales_document (id, kind, number, party_id, document_date, status, amount_e4, "
                    + "total_e4, fiscal_year_id, voucher_id, created_at, updated_at) VALUES ('old', 'SALES_INVOICE', "
                    + "'INV/26-27/00001', 'p1', '2026-05-01', 'POSTED', 1000000, 1000000, 'fy', 'v1', 0, 0)");

            new MigrationRunner(Schemas.companyDatabase(), dir.resolve("b2")).migrate(database);

            assertThat(scalar(database, "SELECT taxable_e4 || '/' || tax_e4 || '/' || amount_e4 FROM sales_document"))
                    .isEqualTo("1000000/0/1000000");
            assertThat(scalar(database, "SELECT COUNT(*) FROM ledger WHERE group_id = 'g1'")).isEqualTo("15");
            assertThatThrownBy(() -> exec(database, "UPDATE sales_document SET place_of_supply = '29'"))
                    .as("the new columns are frozen on a posted document").hasMessageContaining("cannot be changed");
        }
    }

    @Test
    @DisplayName("P9-8: a document cannot post without its GST basis, by update or by insert")
    void postsOnlyWithBasis(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            assertThatThrownBy(() -> exec(database, POST)).hasMessageContaining("GST basis");
            assertThatThrownBy(() -> exec(database, "INSERT INTO sales_document (id, kind, number, party_id, "
                    + "document_date, status, amount_e4, taxable_e4, total_e4, fiscal_year_id, voucher_id, created_at, "
                    + "updated_at) VALUES ('d2', 'SALES_INVOICE', 'X', 'p1', '2026-05-01', 'POSTED', 100, 100, 100, "
                    + "(SELECT id FROM fiscal_year), 'v1', 0, 0)")).hasMessageContaining("GST basis");
            assertThatCode(() -> exec(database, POST + BASIS)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the amount is taxable value plus tax - or the taxable value alone under reverse charge")
    void amountRule(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            assertThatThrownBy(() -> exec(database, "UPDATE sales_document SET taxable_e4 = 1000000, tax_e4 = 180000, "
                    + "amount_e4 = 1000000, total_e4 = 1000000")).hasMessageContaining("taxable value plus tax");
            assertThatCode(() -> exec(database, "UPDATE sales_document SET taxable_e4 = 1000000, tax_e4 = 180000, "
                    + "reverse_charge = 1, amount_e4 = 1000000, total_e4 = 1000000")).doesNotThrowAnyException();
            assertThatThrownBy(() -> exec(database, "UPDATE sales_document SET reverse_charge = 0"))
                    .hasMessageContaining("taxable value plus tax");
        }
    }

    @Test
    @DisplayName("tax lines: known components, whole paise, one per component, frozen once posted")
    void taxLines(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            assertThatThrownBy(() -> exec(database, "INSERT INTO sales_tax_line (id, line_id, component, rate_e4, "
                    + "amount_e4) VALUES ('t0', 'l1', 'VAT', 90000, 90000)")).hasMessageContaining("CHECK");
            assertThatThrownBy(() -> exec(database, "INSERT INTO sales_tax_line (id, line_id, component, rate_e4, "
                    + "amount_e4) VALUES ('t0', 'l1', 'CGST', 90000, 90050)")).hasMessageContaining("CHECK");
            exec(database, "INSERT INTO sales_tax_line (id, line_id, component, rate_e4, amount_e4) "
                    + "VALUES ('t1', 'l1', 'CGST', 90000, 90000), ('t2', 'l1', 'SGST', 90000, 90000)");
            assertThatThrownBy(() -> exec(database, "INSERT INTO sales_tax_line (id, line_id, component, rate_e4, "
                    + "amount_e4) VALUES ('t3', 'l1', 'CGST', 90000, 90000)")).hasMessageContaining("UNIQUE");

            exec(database, POST + BASIS);

            assertThatThrownBy(() -> exec(database, "UPDATE sales_tax_line SET amount_e4 = 0"))
                    .hasMessageContaining("cannot be changed");
            assertThatThrownBy(() -> exec(database, "DELETE FROM sales_tax_line"))
                    .hasMessageContaining("cannot be changed");
            assertThatThrownBy(() -> exec(database, "INSERT INTO sales_tax_line (id, line_id, component, rate_e4, "
                    + "amount_e4) VALUES ('t4', 'l1', 'CESS', 10000, 10000)")).hasMessageContaining("cannot be changed");
        }
    }

    @Test
    @DisplayName("item tax: taxable needs a rate, untaxed carries none, one entry per item per date")
    void itemTax(@TempDir Path dir) throws Exception {
        try (Database database = company(dir)) {
            exec(database, "INSERT INTO unit (id, symbol, name, decimals, created_at, updated_at) "
                    + "VALUES ('u', 'NOS', 'Numbers', 0, 0, 0)");
            exec(database, "INSERT INTO item (id, unit_id, name, kind, created_at, updated_at) "
                    + "VALUES ('i', 'u', 'Lamp', 'GOODS', 0, 0)");
            String insert = "INSERT INTO item_tax (id, item_id, effective_from, category, gst_rate_e4, cess_rate_e4, "
                    + "rule_pack_version, created_at, updated_at) VALUES ";
            assertThatThrownBy(() -> exec(database, insert + "('a', 'i', '2026-04-01', 'TAXABLE', 0, 0, 'v', 0, 0)"))
                    .hasMessageContaining("CHECK");
            assertThatThrownBy(() -> exec(database, insert + "('a', 'i', '2026-04-01', 'EXEMPT', 50000, 0, 'v', 0, 0)"))
                    .hasMessageContaining("CHECK");
            exec(database, insert + "('a', 'i', '2026-04-01', 'TAXABLE', 180000, 0, 'v', 0, 0)");
            assertThatThrownBy(() -> exec(database, insert + "('b', 'i', '2026-04-01', 'TAXABLE', 50000, 0, 'v', 0, 0)"))
                    .hasMessageContaining("UNIQUE");
        }
    }
}
