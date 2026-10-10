package com.accubook.inventory;

import com.accubook.app.company.CompanyService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.stock.StockPort;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Stock port")
class InventoryStockPortIT {

    private static final LocalDate DAY = LocalDate.of(2026, 5, 1);

    @Test
    @DisplayName("the inventory module is found through ServiceLoader as the build's stock port")
    void discovered() {
        assertThat(StockPort.installed()).hasValueSatisfying(port ->
                assertThat(port).isInstanceOf(InventoryStockPort.class));
    }

    @Test
    @DisplayName("moves stock inside the caller's transaction, under inventory's own rules")
    void movesWithinTransaction(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir,
                ModuleRegistry.discover(List.of(new InventoryModule())), id -> ModuleState.ACTIVE)) {
            service.openCompany(service.createCompany("Asha Traders").id());
            Inventory inventory = new Inventory(service.openCompanyTransactions(),
                    new EntitlementGuard(id -> ModuleState.ACTIVE));
            Unit nos = inventory.createUnit("NOS", "Numbers", 0);
            Item pen = inventory.createItem("Pen", ItemKind.GOODS, nos.id(), null, null, null, null);
            Warehouse main = inventory.createWarehouse("Main", null);
            StockPort port = new InventoryStockPort();

            service.openCompanyTransactions().inTransaction(connection -> {
                assertThat(port.item(connection, pen.id())).hasValueSatisfying(facts -> {
                    assertThat(facts.goods()).isTrue();
                    assertThat(facts.unitDecimals()).isZero();
                });
                port.move(connection, StockPort.Kind.PURCHASE, pen.id(), main.id(), new BigDecimal("10"),
                        new BigDecimal("5"), DAY, "PURCHASE_BILL", "pb-1", null, null, null);
                port.move(connection, StockPort.Kind.SALE, pen.id(), main.id(), new BigDecimal("4"),
                        null, DAY, "SALES_INVOICE", "inv-1", null, null, null);
                return null;
            });
            assertThat(inventory.stockOf(pen.id(), main.id())).isEqualByComparingTo("6");

            // A refusal inside the caller's transaction rolls back the caller's work too.
            assertThatThrownBy(() -> service.openCompanyTransactions().inTransaction(connection -> {
                port.move(connection, StockPort.Kind.PURCHASE, pen.id(), main.id(), BigDecimal.ONE,
                        null, DAY, null, null, null, null, null);
                port.move(connection, StockPort.Kind.SALE, pen.id(), main.id(), new BigDecimal("100"),
                        null, DAY, null, null, null, null, null);
                return null;
            })).isInstanceOf(InventoryException.class);
            assertThat(inventory.stockOf(pen.id(), main.id())).isEqualByComparingTo("6");
        }
    }
}
