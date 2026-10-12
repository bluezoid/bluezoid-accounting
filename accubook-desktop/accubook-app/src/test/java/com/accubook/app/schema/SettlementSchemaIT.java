package com.accubook.app.schema;

import com.accubook.app.company.CompanyService;
import com.accubook.persistence.connection.Database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migration 7: the allocation limits and lifecycle rules hold in the database itself. */
@DisplayName("Settlement schema")
class SettlementSchemaIT {

    @TempDir
    Path dir;

    private Database database;

    private void exec(String sql) throws SQLException {
        database.inUnguardedTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    @BeforeEach
    void setUp() throws Exception {
        String id;
        try (CompanyService service = CompanyService.start(dir)) {
            id = service.createCompany("Asha Traders").id();
        }
        database = Database.open(dir.resolve("companies").resolve(id).resolve("company.db"));
        exec("INSERT INTO party (id, ledger_id, role, name, created_at, updated_at) "
                + "SELECT 'p1', id, 'CUSTOMER', 'Round Off', 0, 0 FROM ledger WHERE system_code = 'ROUND_OFF'");
        exec("INSERT INTO party (id, ledger_id, role, name, created_at, updated_at) "
                + "SELECT 'p2', id, 'CUSTOMER', 'Opening Balance Equity', 0, 0 FROM ledger "
                + "WHERE system_code = 'OPENING_BALANCE_EQUITY'");
        exec("INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, voucher_date, "
                + "total_debit_e4, total_credit_e4, status, is_posted, posted_at, created_at, updated_at) "
                + "SELECT 'v1', (SELECT id FROM fiscal_year), (SELECT id FROM voucher_type WHERE code = 'SALES'), "
                + "'1', '2026-05-01', 1000000, 1000000, 'POSTED', 1, 0, 0, 0");
        // A posted invoice of 1,000 for p1 and a receipt of 700 from p1.
        exec("INSERT INTO sales_document (id, kind, number, party_id, document_date, status, amount_e4, "
                + "taxable_e4, total_e4, fiscal_year_id, voucher_id, gst_scheme, place_of_supply, supply_type, "
                + "rule_pack_version, created_at, updated_at) VALUES ('inv', 'SALES_INVOICE', "
                + "'INV/26-27/00001', 'p1', '2026-05-01', 'POSTED', 10000000, 10000000, 10000000, "
                + "(SELECT id FROM fiscal_year), 'v1', 'REGULAR', '27', 'INTRA_STATE', '2025.10.1', 0, 0)");
        exec("INSERT INTO sales_settlement (id, number, party_id, settlement_date, amount_e4, account_ledger_id, "
                + "mode, fiscal_year_id, voucher_id, created_at, updated_at) VALUES ('r1', 'RCT/26-27/00001', 'p1', "
                + "'2026-05-02', 7000000, (SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'), 'CASH', "
                + "(SELECT id FROM fiscal_year), 'v1', 0, 0)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        database.close();
    }

    private static String allocate(String id, String sourceId, String documentId, long amountE4) {
        return "INSERT INTO sales_allocation (id, source_type, source_id, document_id, amount_e4, effective_date, "
                + "created_at) VALUES ('" + id + "', 'SETTLEMENT', '" + sourceId + "', '" + documentId + "', "
                + amountE4 + ", '2026-05-02', 0)";
    }

    @Test
    @DisplayName("D-21: a receipt cannot allocate more than it holds, even by direct SQL")
    void sourceLimit() throws SQLException {
        exec(allocate("a1", "r1", "inv", 7000000));
        exec("INSERT INTO sales_document (id, kind, number, party_id, document_date, status, amount_e4, taxable_e4, "
                + "total_e4, fiscal_year_id, voucher_id, gst_scheme, place_of_supply, supply_type, rule_pack_version, "
                + "created_at, updated_at) VALUES ('inv2', 'SALES_INVOICE', "
                + "'INV/26-27/00002', 'p1', '2026-05-01', 'POSTED', 100, 100, 100, (SELECT id FROM fiscal_year), 'v1', "
                + "'REGULAR', '27', 'INTRA_STATE', '2025.10.1', 0, 0)");
        assertThatThrownBy(() -> exec(allocate("a2", "r1", "inv2", 100))).hasMessageContaining("holds");
    }

    @Test
    @DisplayName("an invoice cannot receive more than its total")
    void documentLimit() throws SQLException {
        exec("INSERT INTO sales_settlement (id, number, party_id, settlement_date, amount_e4, account_ledger_id, mode, "
                + "fiscal_year_id, voucher_id, created_at, updated_at) VALUES ('r2', 'RCT/26-27/00002', 'p1', "
                + "'2026-05-03', 5000000, (SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'), 'UPI', "
                + "(SELECT id FROM fiscal_year), 'v1', 0, 0)");
        exec(allocate("a1", "r1", "inv", 7000000));
        assertThatThrownBy(() -> exec(allocate("a2", "r2", "inv", 3000100))).hasMessageContaining("document total");
        assertThatCode(() -> exec(allocate("a3", "r2", "inv", 3000000))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an allocation needs a source of the same party, and a posted invoice as target")
    void sourceAndTarget() {
        assertThatThrownBy(() -> exec(allocate("a1", "nope", "inv", 100))).hasMessageContaining("same party");
        assertThatThrownBy(() -> exec("UPDATE sales_document SET party_id = 'p2'"))
                .as("posted document is frozen").hasMessageContaining("cannot be changed");
    }

    @Test
    @DisplayName("allocations are never edited; allocated things cannot be cancelled underneath them")
    void lifecycle() throws SQLException {
        exec(allocate("a1", "r1", "inv", 100));
        assertThatThrownBy(() -> exec("UPDATE sales_allocation SET amount_e4 = 200")).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> exec(allocate("a2", "r1", "inv", 100)))
                .as("one live allocation per source and document").hasMessageContaining("UNIQUE");
        assertThatThrownBy(() -> exec("UPDATE sales_settlement SET status = 'CANCELLED', cancellation_voucher_id = 'v1'"))
                .hasMessageContaining("allocations");
        assertThatThrownBy(() -> exec("UPDATE sales_document SET status = 'CANCELLED', cancellation_voucher_id = 'v1'"))
                .hasMessageContaining("allocations");

        assertThatThrownBy(() -> exec("DELETE FROM sales_allocation")).hasMessageContaining("never deleted");
        exec("UPDATE sales_allocation SET removed_date = '2026-05-03', removal_reason = 'Cheque bounced'");
        assertThatThrownBy(() -> exec("UPDATE sales_allocation SET removed_date = NULL, removal_reason = NULL"))
                .as("a removal is not undone").hasMessageContaining("cannot be changed");
        assertThatCode(() -> exec("UPDATE sales_settlement SET status = 'CANCELLED', cancellation_voucher_id = 'v1'"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a receipt is frozen and never deleted; amounts are whole paise")
    void receiptFrozen() {
        assertThatThrownBy(() -> exec("UPDATE sales_settlement SET amount_e4 = 1")).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> exec("DELETE FROM sales_settlement")).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> exec(allocate("a1", "r1", "inv", 150))).hasMessageContaining("CHECK");
    }
}
