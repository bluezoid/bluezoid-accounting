package com.accubook.app.schema;

import com.accubook.app.company.CompanyService;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migration 8: quotation and order rules the database enforces itself. */
@DisplayName("Order schema")
class OrderSchemaIT {

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;

    private void exec(String sql) throws SQLException {
        transactions.inTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        exec("INSERT INTO party (id, ledger_id, role, name, created_at, updated_at) "
                + "SELECT 'p1', id, 'BOTH', 'Round Off', 0, 0 FROM ledger WHERE system_code = 'ROUND_OFF'");
        exec("INSERT INTO unit (id, symbol, name, decimals, created_at, updated_at) VALUES ('u', 'NOS', 'Nos', 0, 0, 0)");
        exec("INSERT INTO item (id, unit_id, name, kind, created_at, updated_at) VALUES ('i', 'u', 'Pen', 'GOODS', 0, 0)");
        exec("INSERT INTO sales_order (id, party_id, order_date, created_at, updated_at) "
                + "VALUES ('o1', 'p1', '2026-05-01', 0, 0)");
        exec("INSERT INTO sales_order_line (id, order_id, sequence, item_id, description, quantity_e4, rate_e4, "
                + "amount_e4) VALUES ('l1', 'o1', 1, 'i', 'Pen', 100000, 100000, 1000000)");
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    @Test
    @DisplayName("a draft has no number; confirming gives one")
    void numberOnConfirmation() {
        assertThatThrownBy(() -> exec("UPDATE sales_order SET number = 'SO/26-27/00001'")).hasMessageContaining("CHECK");
        assertThatCode(() -> exec("UPDATE sales_order SET number = 'SO/26-27/00001', status = 'CONFIRMED'"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a confirmed order and its lines are frozen; it can only be closed or cancelled, with a reason")
    void frozenAfterConfirmation() throws SQLException {
        exec("UPDATE sales_order SET number = 'SO/26-27/00001', status = 'CONFIRMED'");

        assertThatThrownBy(() -> exec("UPDATE sales_order SET order_date = '2026-05-02'")).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> exec("UPDATE sales_order_line SET quantity_e4 = 1")).hasMessageContaining("Lines cannot change");
        assertThatThrownBy(() -> exec("DELETE FROM sales_order")).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> exec("UPDATE sales_order SET status = 'CLOSED'")).hasMessageContaining("CHECK");
        assertThatCode(() -> exec("UPDATE sales_order SET status = 'CLOSED', close_reason = 'Customer took less'"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> exec("UPDATE sales_order SET status = 'CONFIRMED', close_reason = NULL"))
                .hasMessageContaining("cannot be changed");
    }

    @Test
    @DisplayName("a quotation cannot be valid until before its own date, and is frozen once sent")
    void quotation() throws SQLException {
        assertThatThrownBy(() -> exec("INSERT INTO sales_quotation (id, party_id, quotation_date, valid_until, "
                + "created_at, updated_at) VALUES ('q0', 'p1', '2026-05-10', '2026-05-09', 0, 0)")).hasMessageContaining("CHECK");
        exec("INSERT INTO sales_quotation (id, party_id, quotation_date, valid_until, created_at, updated_at) "
                + "VALUES ('q1', 'p1', '2026-05-01', '2026-05-31', 0, 0)");
        exec("UPDATE sales_quotation SET status = 'SENT', number = 'QT/26-27/00001'");

        assertThatThrownBy(() -> exec("UPDATE sales_quotation SET valid_until = '2026-06-30'")).hasMessageContaining("cannot be changed");
        assertThatCode(() -> exec("UPDATE sales_quotation SET status = 'ACCEPTED'")).doesNotThrowAnyException();
    }
}
