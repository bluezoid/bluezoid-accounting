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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migration 12: TDS and TCS records, what a party settles, and receipts with TDS. */
@DisplayName("Withholding schema")
class WithholdingSchemaIT {

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

    private String scalar(String sql) throws SQLException {
        try (var connection = database.openReadConnection();
             Statement s = connection.createStatement();
             var rows = s.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static final String BASIS = "'REGULAR', '27', 'INTRA_STATE', '2025.10.2'";

    @BeforeEach
    void setUp() throws Exception {
        String id;
        try (CompanyService service = CompanyService.start(dir)) {
            id = service.createCompany("Asha Traders").id();
        }
        database = Database.open(dir.resolve("companies").resolve(id).resolve("company.db"));
        exec("INSERT INTO party (id, ledger_id, role, name, created_at, updated_at) "
                + "SELECT 'p1', id, 'BOTH', 'Round Off', 0, 0 FROM ledger WHERE system_code = 'ROUND_OFF'");
        exec("INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, voucher_date, "
                + "total_debit_e4, total_credit_e4, status, is_posted, posted_at, created_at, updated_at) "
                + "SELECT 'v1', (SELECT id FROM fiscal_year), (SELECT id FROM voucher_type WHERE code = 'PURCHASE'), "
                + "'1', '2026-05-01', 1000000, 1000000, 'POSTED', 1, 0, 0, 0");
        // A posted bill of 1,00,000 with TDS of 2,000: the vendor settles 98,000.
        exec("INSERT INTO purchase_document (id, kind, number, party_id, document_date, status, amount_e4, "
                + "taxable_e4, total_e4, fiscal_year_id, voucher_id, gst_scheme, place_of_supply, supply_type, "
                + "rule_pack_version, withholding_section, tds_e4, created_at, updated_at) VALUES ('b1', 'PURCHASE_BILL', "
                + "'PB/26-27/00001', 'p1', '2026-05-01', 'POSTED', 1000000000, 1000000000, 1000000000, "
                + "(SELECT id FROM fiscal_year), 'v1', " + BASIS + ", '194C-OTH', 20000000, 0, 0)");
        exec("INSERT INTO purchase_settlement (id, number, party_id, settlement_date, amount_e4, account_ledger_id, "
                + "mode, fiscal_year_id, voucher_id, created_at, updated_at) VALUES ('pay', 'PAY/26-27/00001', 'p1', "
                + "'2026-05-02', 1000000000, (SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'), 'CASH', "
                + "(SELECT id FROM fiscal_year), 'v1', 0, 0)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        database.close();
    }

    @Test
    @DisplayName("P9-13: TDS Payable, TDS Receivable and TCS Payable are seeded")
    void ledgers() throws SQLException {
        assertThat(scalar("SELECT COUNT(*) FROM ledger WHERE system_code IN "
                + "('TDS_PAYABLE', 'TDS_RECEIVABLE', 'TCS_PAYABLE')")).isEqualTo("3");
    }

    @Test
    @DisplayName("a bill with TDS settles total - TDS; more cannot be allocated to it")
    void settleNetOfTds() throws SQLException {
        assertThat(scalar("SELECT settle_e4 FROM purchase_document WHERE id = 'b1'")).isEqualTo("980000000");
        String allocate = "INSERT INTO purchase_allocation (id, source_type, source_id, document_id, amount_e4, "
                + "effective_date, created_at) VALUES ('a%s', 'SETTLEMENT', 'pay', 'b1', %d, '2026-05-02', 0)";
        assertThatThrownBy(() -> exec(allocate.formatted("1", 980000100L))).hasMessageContaining("document total");
        assertThatCode(() -> exec(allocate.formatted("2", 980000000L))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("TDS stays on purchases, TCS on sales; a posted bill's TDS is frozen")
    void sidesAndFrozen() throws SQLException {
        exec("INSERT INTO purchase_document (id, kind, party_id, document_date, created_at, updated_at) "
                + "VALUES ('d1', 'PURCHASE_BILL', 'p1', '2026-05-01', 0, 0)");
        assertThatThrownBy(() -> exec("UPDATE purchase_document SET tcs_e4 = 100 WHERE id = 'd1'"))
                .hasMessageContaining("TDS is deducted on purchases");
        assertThatThrownBy(() -> exec("UPDATE purchase_document SET tds_e4 = 100 WHERE id = 'd1'"))
                .as("not above the total").hasMessageContaining("never above the total");
        assertThatThrownBy(() -> exec("UPDATE purchase_document SET status = 'CANCELLED', "
                + "cancellation_voucher_id = 'v1', cancellation_reason = 'x', tds_e4 = 0 WHERE id = 'b1'"))
                .hasMessageContaining("cannot be changed");
    }

    @Test
    @DisplayName("a receipt's TDS needs a section, is less than the receipt, and never sits on a payment")
    void receiptTds() {
        String receipt = "INSERT INTO sales_settlement (id, number, party_id, settlement_date, amount_e4, "
                + "account_ledger_id, mode, fiscal_year_id, voucher_id, tds_e4, tds_section, created_at, updated_at) "
                + "VALUES ('%s', '%s', 'p1', '2026-05-02', 1000000, "
                + "(SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'), 'NEFT', (SELECT id FROM fiscal_year), "
                + "'v1', %d, %s, 0, 0)";
        assertThatThrownBy(() -> exec(receipt.formatted("r1", "R1", 20000, "NULL"))).hasMessageContaining("section");
        assertThatThrownBy(() -> exec(receipt.formatted("r1", "R1", 1000000, "'194J-PROF'")))
                .hasMessageContaining("less than");
        assertThatCode(() -> exec(receipt.formatted("r2", "R2", 100000, "'194J-PROF'"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> exec("INSERT INTO purchase_settlement (id, number, party_id, settlement_date, "
                + "amount_e4, account_ledger_id, mode, fiscal_year_id, voucher_id, tds_e4, tds_section, created_at, "
                + "updated_at) VALUES ('p2', 'P2', 'p1', '2026-05-02', 1000000, (SELECT id FROM ledger WHERE "
                + "system_code = 'CASH_IN_HAND'), 'NEFT', (SELECT id FROM fiscal_year), 'v1', 100, '194C', 0, 0)"))
                .hasMessageContaining("section");
    }

    @Test
    @DisplayName("a withholding entry is only cancelled, never edited or deleted; signs agree")
    void entries() throws SQLException {
        String insert = "INSERT INTO withholding_entry (id, kind, direction, source_type, source_id, party_id, section, "
                + "entry_date, base_e4, taxed_base_e4, rate_e4, amount_e4, created_at) VALUES ";
        assertThatThrownBy(() -> exec(insert + "('e0', 'TDS', 'PAYABLE', 'PURCHASE_DOCUMENT', 'x', 'p1', '194C-OTH', "
                + "'2026-05-01', -100, 100, 20000, 200, 0)")).hasMessageContaining("CHECK");
        exec(insert + "('e1', 'TDS', 'PAYABLE', 'PURCHASE_DOCUMENT', 'b1', 'p1', '194C-OTH', '2026-05-01', "
                + "1000000000, 1000000000, 20000, 20000000, 0)");
        assertThatThrownBy(() -> exec("UPDATE withholding_entry SET amount_e4 = 0")).hasMessageContaining("only ever");
        assertThatThrownBy(() -> exec("DELETE FROM withholding_entry")).hasMessageContaining("never deleted");
        assertThatCode(() -> exec("UPDATE withholding_entry SET status = 'CANCELLED', cancelled_date = '2026-05-03'"))
                .doesNotThrowAnyException();
    }
}
