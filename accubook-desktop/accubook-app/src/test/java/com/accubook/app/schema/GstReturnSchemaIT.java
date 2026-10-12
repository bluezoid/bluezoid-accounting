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

/** Migration 13: filed GST returns are snapshots, never edited, and lock their period (P9-14). */
@DisplayName("GST return schema")
class GstReturnSchemaIT {

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

    private static final String HASH = "0".repeat(64);

    private static String filed(String id, String type, String from, String to) {
        return "INSERT INTO gst_return (id, return_type, period_from, period_to, gstin, filed_on, snapshot, "
                + "snapshot_sha256, rule_pack_version, created_at) VALUES ('" + id + "', '" + type + "', '" + from
                + "', '" + to + "', '27PQRSX5678K1ZY', '2026-06-11', '{}', '" + HASH + "', '2025.10.3', 0)";
    }

    private static String post(String side) {
        return "UPDATE " + side + "_document SET status = 'POSTED', number = 'X/1', taxable_e4 = 1000000, "
                + "amount_e4 = 1000000, total_e4 = 1000000, voucher_id = 'v1', fiscal_year_id = (SELECT id FROM "
                + "fiscal_year), gst_scheme = 'REGULAR', place_of_supply = '27', supply_type = 'INTRA_STATE', "
                + "rule_pack_version = '2025.10.3' WHERE id = 'd1'";
    }

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
                + "SELECT 'v1', (SELECT id FROM fiscal_year), (SELECT id FROM voucher_type WHERE code = 'SALES'), "
                + "'1', '2026-05-10', 1000000, 1000000, 'POSTED', 1, 0, 0, 0");
        for (String side : new String[]{"sales", "purchase"}) {
            exec("INSERT INTO " + side + "_document (id, kind, party_id, document_date, created_at, updated_at) "
                    + "VALUES ('d1', '" + (side.equals("sales") ? "SALES_INVOICE" : "PURCHASE_BILL")
                    + "', 'p1', '2026-05-10', 0, 0)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        database.close();
    }

    @Test
    @DisplayName("a filed GSTR-1 locks sales dated in its period, not purchases")
    void gstr1LocksSales() throws SQLException {
        exec(filed("r1", "GSTR1", "2026-05-01", "2026-05-31"));

        assertThatThrownBy(() -> exec(post("sales"))).hasMessageContaining("return for this document's period is filed");
        assertThatCode(() -> exec(post("purchase"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a filed GSTR-3B locks both sides; unlocking with a reason opens the period again")
    void gstr3bLocksBoth() throws SQLException {
        exec(filed("r1", "GSTR3B", "2026-05-01", "2026-05-31"));
        assertThatThrownBy(() -> exec(post("purchase"))).hasMessageContaining("filed");

        exec("UPDATE gst_return SET status = 'UNLOCKED', unlocked_on = '2026-06-20', unlock_reason = 'Amendment'");

        assertThatCode(() -> exec(post("purchase"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a posted document in a filed period cannot be cancelled either")
    void cancellationLocked() throws SQLException {
        exec(post("sales"));
        exec(filed("r1", "GSTR1", "2026-05-01", "2026-05-31"));

        assertThatThrownBy(() -> exec("UPDATE sales_document SET status = 'CANCELLED', cancellation_voucher_id = 'v1', "
                + "cancellation_reason = 'x' WHERE id = 'd1'")).hasMessageContaining("filed");
    }

    @Test
    @DisplayName("a filed return is never edited or deleted, and one return per type and period is filed")
    void immutable() throws SQLException {
        exec(filed("r1", "GSTR1", "2026-05-01", "2026-05-31"));

        assertThatThrownBy(() -> exec("UPDATE gst_return SET snapshot = '{\"x\":1}'")).hasMessageContaining("never edited");
        assertThatThrownBy(() -> exec("DELETE FROM gst_return")).hasMessageContaining("never deleted");
        assertThatThrownBy(() -> exec(filed("r2", "GSTR1", "2026-05-01", "2026-05-31"))).hasMessageContaining("UNIQUE");
        assertThatThrownBy(() -> exec("UPDATE gst_return SET status = 'UNLOCKED'")).hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("a unit's UQC is three letters")
    void unitUqc() {
        assertThatThrownBy(() -> exec("INSERT INTO unit (id, symbol, name, decimals, uqc, created_at, updated_at) "
                + "VALUES ('u', 'KG', 'Kilograms', 3, 'KG', 0, 0)")).hasMessageContaining("CHECK");
    }
}
