package com.accubook.inventory;

import com.accubook.app.company.CompanyService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleNotWritableException;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory on a real company: shipped migrations, opened through
 * {@link CompanyService} with the persistence guard installed from a registry
 * that contains this module - the production arrangement.
 */
@DisplayName("Inventory")
class InventoryIT {

    private static final LocalDate DAY = LocalDate.of(2026, 5, 1);

    @TempDir
    Path dir;

    private final Map<String, ModuleState> states = new HashMap<>(Map.of("inventory", ModuleState.ACTIVE));
    private CompanyService service;
    private ModuleTransactions transactions;
    private Inventory inventory;

    private Unit nos;
    private Unit kg;
    private Item rice;
    private Warehouse main;
    private Warehouse shop;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())),
                id -> states.getOrDefault(id, ModuleState.ACTIVE));
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        inventory = new Inventory(transactions, new EntitlementGuard(service.moduleStates()));

        nos = inventory.createUnit("NOS", "Numbers", 0);
        kg = inventory.createUnit("KG", "Kilograms", 3);
        rice = inventory.createItem("Basmati Rice", ItemKind.GOODS, kg.id(), "RICE-1", null, "1006", null);
        main = inventory.createWarehouse("Main Location", null);
        shop = inventory.createWarehouse("Shop", "MG Road");
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal q(String value) {
        return new BigDecimal(value);
    }

    private void buy(Item item, Warehouse warehouse, String quantity) {
        inventory.record(MovementRequest.in(MovementType.PURCHASE, item.id(), warehouse.id(), q(quantity),
                q("80"), DAY, null));
    }

    private void sell(Item item, Warehouse warehouse, String quantity) {
        inventory.record(MovementRequest.out(MovementType.SALE, item.id(), warehouse.id(), q(quantity), DAY, null));
    }

    private long movementCount() throws SQLException {
        return transactions.read(connection -> {
            try (Statement s = connection.createStatement();
                 var rows = s.executeQuery("SELECT COUNT(*) FROM stock_movement")) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }

    @Nested
    @DisplayName("masters")
    class Masters {

        @Test
        @DisplayName("creates and lists units, categories, items and warehouses")
        void createAndList() {
            ItemCategory grains = inventory.createCategory("Grains", null);
            inventory.createItem("Delivery", ItemKind.SERVICE, nos.id(), null, grains.id(), "9965", null);

            assertThat(inventory.units()).extracting(Unit::symbol).containsExactly("KG", "NOS");
            assertThat(inventory.categories()).extracting(ItemCategory::name).containsExactly("Grains");
            assertThat(inventory.items()).extracting(Item::name).containsExactly("Basmati Rice", "Delivery");
            assertThat(inventory.warehouses()).extracting(Warehouse::name).containsExactly("Main Location", "Shop");
            assertThat(inventory.findItem(rice.id())).contains(rice);
        }

        @Test
        @DisplayName("an item name already in use is refused, ignoring case")
        void duplicateItem() {
            assertThatThrownBy(() -> inventory.createItem("BASMATI RICE", ItemKind.GOODS, kg.id(), null,
                    null, null, null))
                    .isInstanceOf(InventoryException.class).hasMessageContaining("already in use");
        }

        @Test
        @DisplayName("a unit allows at most four decimal places")
        void unitDecimals() {
            assertThatThrownBy(() -> inventory.createUnit("MG", "Milligrams", 5))
                    .isInstanceOf(InventoryException.class);
        }

        @Test
        @DisplayName("an item's unit can change until it first moves, never after")
        void unitFixedOnceMoved() {
            inventory.changeItemUnit(rice.id(), nos.id());
            inventory.changeItemUnit(rice.id(), kg.id());
            buy(rice, main, "10");

            assertThatThrownBy(() -> inventory.changeItemUnit(rice.id(), nos.id()))
                    .isInstanceOf(InventoryException.class).hasMessageContaining("reinterpreted");
        }

        @Test
        @DisplayName("renaming keeps the item's identity and history")
        void rename() {
            buy(rice, main, "10");
            inventory.updateItemDetails(rice.id(), "Basmati Rice 1121", "RICE-1121", null, "1006", null);

            assertThat(inventory.findItem(rice.id()).orElseThrow().name()).isEqualTo("Basmati Rice 1121");
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("10");
        }
    }

    @Nested
    @DisplayName("stock movements")
    class Movements {

        @Test
        @DisplayName("purchases add stock, sales remove it, per warehouse")
        void inAndOut() {
            buy(rice, main, "25.5");
            sell(rice, main, "10.25");

            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("15.25");
            assertThat(inventory.stockOf(rice.id(), shop.id())).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("P7-4: a sale beyond stock on hand is refused and leaves nothing behind")
        void negativeStockRefused() throws SQLException {
            buy(rice, main, "5");
            long movementsBefore = movementCount();

            assertThatThrownBy(() -> sell(rice, main, "5.001"))
                    .isInstanceOf(InventoryException.class)
                    .hasMessageContaining("Only 5 KG")
                    .hasMessageContaining("below zero");

            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("5");
            assertThat(movementCount()).isEqualTo(movementsBefore);
        }

        @Test
        @DisplayName("stock in one warehouse cannot be sold from another")
        void perWarehouse() {
            buy(rice, main, "5");
            assertThatThrownBy(() -> sell(rice, shop, "1")).isInstanceOf(InventoryException.class);
        }

        @Test
        @DisplayName("a transfer moves stock and creates none")
        void transfer() {
            buy(rice, main, "10");
            inventory.record(MovementRequest.transfer(rice.id(), main.id(), shop.id(), q("4"), DAY, null));

            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("6");
            assertThat(inventory.stockOf(rice.id(), shop.id())).isEqualByComparingTo("4");
            assertThatThrownBy(() -> inventory.record(
                    MovementRequest.transfer(rice.id(), main.id(), shop.id(), q("7"), DAY, null)))
                    .isInstanceOf(InventoryException.class);
        }

        @Test
        @DisplayName("a purchase return takes stock back out")
        void purchaseReturn() {
            buy(rice, main, "10");
            inventory.record(MovementRequest.out(MovementType.PURCHASE_RETURN, rice.id(), main.id(), q("3"), DAY, null));
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("7");
        }

        @Test
        @DisplayName("a quantity finer than the unit allows is refused, never rounded")
        void unitPrecision() {
            Item pens = inventory.createItem("Pens", ItemKind.GOODS, nos.id(), null, null, null, null);
            assertThatThrownBy(() -> buy(pens, main, "2.5"))
                    .isInstanceOf(InventoryException.class).hasMessageContaining("0 decimal places");
            assertThatThrownBy(() -> buy(rice, main, "1.0005"))
                    .isInstanceOf(InventoryException.class).hasMessageContaining("3 decimal places");
            buy(pens, main, "2.000");
            assertThat(inventory.stockOf(pens.id(), main.id())).isEqualByComparingTo("2");
        }

        @Test
        @DisplayName("services, inactive items and inactive warehouses do not move")
        void refusals() {
            Item delivery = inventory.createItem("Delivery", ItemKind.SERVICE, nos.id(), null, null, null, null);
            assertThatThrownBy(() -> buy(delivery, main, "1")).hasMessageContaining("service");

            inventory.deactivateItem(rice.id());
            assertThatThrownBy(() -> buy(rice, main, "1")).hasMessageContaining("inactive");
            inventory.reactivateItem(rice.id());

            inventory.deactivateWarehouse(shop.id());
            assertThatThrownBy(() -> buy(rice, shop, "1")).hasMessageContaining("inactive");
        }

        @Test
        @DisplayName("a warehouse holding stock cannot be deactivated")
        void warehouseWithStock() {
            buy(rice, main, "1");
            assertThatThrownBy(() -> inventory.deactivateWarehouse(main.id()))
                    .isInstanceOf(InventoryException.class).hasMessageContaining("still holds stock");
        }

        @Test
        @DisplayName("the movement log records cost and source for valuation in P8")
        void movementLog() {
            buy(rice, main, "10");
            inventory.record(new MovementRequest(MovementType.SALE, rice.id(), main.id(), null, q("2"), null,
                    DAY, "SALES_INVOICE", "inv-1", "Invoice 1"));

            assertThat(inventory.movementsOf(rice.id()))
                    .extracting(MovementRequest::type, MovementRequest::sourceId)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(MovementType.PURCHASE, null),
                            org.assertj.core.groups.Tuple.tuple(MovementType.SALE, "inv-1"));
            assertThat(inventory.movementsOf(rice.id()).get(0).unitCost()).isEqualByComparingTo("80");
        }
    }

    @Nested
    @DisplayName("stock verification")
    class Verification {

        @Test
        @DisplayName("the cache agrees with the movement log after ordinary use")
        void consistent() {
            buy(rice, main, "10");
            sell(rice, main, "3");
            inventory.record(MovementRequest.transfer(rice.id(), main.id(), shop.id(), q("2"), DAY, null));

            assertThat(inventory.verifyStock()).isEmpty();
        }

        @Test
        @DisplayName("a damaged cache is reported with the log's figure")
        void detectsDamage() throws SQLException {
            buy(rice, main, "10");
            transactions.inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("UPDATE stock SET quantity_e4 = 990000");
                }
            });

            assertThat(inventory.verifyStock()).singleElement().satisfies(d -> {
                assertThat(d.cached()).isEqualByComparingTo("99");
                assertThat(d.fromMovements()).isEqualByComparingTo("10");
            });
        }
    }

    @Nested
    @DisplayName("entitlement")
    class Entitlement {

        @Test
        @DisplayName("READ_ONLY: refused at the use case, and at the guard for a write that skips it")
        void readOnlyBothLayers() {
            buy(rice, main, "10");
            states.put("inventory", ModuleState.READ_ONLY);

            assertThatThrownBy(() -> buy(rice, main, "1")).isInstanceOf(ModuleNotWritableException.class);
            assertThatThrownBy(() -> inventory.createItem("Wheat", ItemKind.GOODS, kg.id(), null, null, null, null))
                    .isInstanceOf(ModuleNotWritableException.class);
            assertThatThrownBy(() -> transactions.inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("UPDATE stock SET quantity_e4 = 0");
                }
            })).isInstanceOf(com.accubook.persistence.guard.UnauthorizedWriteException.class);

            // History stays readable.
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("10");
            assertThat(inventory.items()).isNotEmpty();
            assertThat(inventory.verifyStock()).isEmpty();
        }

        @Test
        @DisplayName("restoring the entitlement restores writing")
        void restored() {
            states.put("inventory", ModuleState.READ_ONLY);
            assertThatThrownBy(() -> buy(rice, main, "1")).isInstanceOf(ModuleNotWritableException.class);

            states.put("inventory", ModuleState.ACTIVE);
            buy(rice, main, "1");
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("1");
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        /**
         * Twelve threads each try to sell one unit of five. Exactly five succeed.
         *
         * <p>Honest scope, as in P5: Database serialises writers, so this proves
         * the end-to-end invariant under contention rather than isolating the
         * guarded UPDATE - that statement's refusal and the schema's CHECK are
         * tested directly elsewhere.
         */
        @Test
        @DisplayName("concurrent sales never oversell")
        void neverOversells() throws Exception {
            Item pens = inventory.createItem("Pens", ItemKind.GOODS, nos.id(), null, null, null, null);
            buy(pens, main, "5");

            ExecutorService pool = Executors.newFixedThreadPool(12);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        sell(pens, main, "1");
                        return true;
                    } catch (InventoryException refused) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int sold = 0;
            for (Future<Boolean> result : results) {
                if (result.get(30, TimeUnit.SECONDS)) {
                    sold++;
                }
            }
            pool.shutdown();

            assertThat(sold).isEqualTo(5);
            assertThat(inventory.stockOf(pens.id(), main.id())).isEqualByComparingTo("0");
            assertThat(inventory.verifyStock()).isEmpty();
        }
    }
}
