package com.accubook.inventory;

import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.accounting.chart.TrialBalanceRow;
import com.accubook.app.company.CompanyService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleNotWritableException;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 8 costing and valuation, with figures worked out by hand in each test. */
@DisplayName("Inventory costing and valuation")
class CostingIT {

    private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);

    @TempDir
    Path dir;

    private final Map<String, ModuleState> states = new HashMap<>();
    private CompanyService service;
    private Inventory inventory;
    private Unit nos;
    private Warehouse main;
    private Warehouse shop;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())),
                id -> states.getOrDefault(id, ModuleState.ACTIVE));
        service.openCompany(service.createCompany("Asha Traders").id());
        inventory = new Inventory(service.openCompanyTransactions(), new EntitlementGuard(service.moduleStates()));
        nos = inventory.createUnit("NOS", "Numbers", 0);
        main = inventory.createWarehouse("Main", null);
        shop = inventory.createWarehouse("Shop", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private Item item(String name, CostingMethod method) {
        Item item = inventory.createItem(name, ItemKind.GOODS, nos.id(), null, null, null, null);
        inventory.setCostingMethod(item.id(), method);
        return item;
    }

    private void buy(Item item, Warehouse w, int day, String quantity, String cost) {
        inventory.record(MovementRequest.in(MovementType.PURCHASE, item.id(), w.id(), d(quantity), d(cost),
                MAY_1.plusDays(day - 1), null));
    }

    private void sell(Item item, Warehouse w, int day, String quantity) {
        inventory.record(MovementRequest.out(MovementType.SALE, item.id(), w.id(), d(quantity), MAY_1.plusDays(day - 1), null));
    }

    private BigDecimal valueOf(Item item, Warehouse w, int day) {
        return inventory.valuation(MAY_1.plusDays(day - 1)).lines().stream()
                .filter(l -> l.itemId().equals(item.id()) && l.warehouseId().equals(w.id()))
                .map(ValuationLine::value).findFirst().orElse(BigDecimal.ZERO);
    }

    private BigDecimal ledgerNet(String name, LocalDate on) throws Exception {
        TrialBalance tb = service.readOpenCompany(connection -> TrialBalanceQuery.compute(connection,
                TrialBalanceQuery.defaultYear(TrialBalanceQuery.fiscalYears(connection), on).orElseThrow().id()));
        assertThat(tb.balances()).isTrue();
        return tb.rows().stream().filter(r -> r.ledgerName().equals(name)).findFirst()
                .map(r -> r.debit().subtract(r.credit())).orElse(BigDecimal.ZERO);
    }

    @Nested
    @DisplayName("methods (P8-1, P8-2)")
    class Methods {

        @Test
        @DisplayName("moving average: 10 @ 100 + 10 @ 130 averages 115; selling 5 takes 575")
        void movingAverage() {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "10", "100");
            buy(pen, main, 2, "10", "130");
            sell(pen, main, 3, "5");

            assertThat(valueOf(pen, main, 2)).isEqualByComparingTo("2300");
            assertThat(valueOf(pen, main, 3)).isEqualByComparingTo("1725");   // 15 x 115
        }

        @Test
        @DisplayName("FIFO: selling 5 then 10 takes 5 @ 100, then 5 @ 100 + 5 @ 130")
        void fifo() {
            Item pen = item("Pen", CostingMethod.FIFO);
            buy(pen, main, 1, "10", "100");
            buy(pen, main, 2, "10", "130");
            sell(pen, main, 3, "5");
            assertThat(valueOf(pen, main, 3)).isEqualByComparingTo("1800");   // 5 @ 100 + 10 @ 130
            sell(pen, main, 4, "10");
            assertThat(valueOf(pen, main, 4)).isEqualByComparingTo("650");    // 5 @ 130
        }

        @Test
        @DisplayName("an emptied warehouse is worth exactly zero, whatever the rounding on the way")
        void exactToZero() {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "3", "10");
            buy(pen, main, 1, "3", "10.01");      // average 10.005
            sell(pen, main, 2, "1");
            sell(pen, main, 2, "1");
            sell(pen, main, 2, "4");

            assertThat(inventory.valuation(MAY_1.plusDays(1)).lines()).isEmpty();
            assertThat(inventory.valuation(MAY_1.plusDays(1)).total()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("the costing method is fixed once the item has moved")
        void methodFixed() {
            Item pen = item("Pen", CostingMethod.FIFO);
            buy(pen, main, 1, "1", "1");
            assertThatThrownBy(() -> inventory.setCostingMethod(pen.id(), CostingMethod.WEIGHTED_AVERAGE))
                    .hasMessageContaining("cannot change");
        }
    }

    @Nested
    @DisplayName("transfers and adjustments (D-31, P8-5)")
    class TransfersAndAdjustments {

        @Test
        @DisplayName("a transfer moves value with the goods; the total is unchanged")
        void transfer() {
            Item pen = item("Pen", CostingMethod.FIFO);
            buy(pen, main, 1, "10", "100");
            buy(pen, main, 2, "10", "130");
            inventory.record(MovementRequest.transfer(pen.id(), main.id(), shop.id(), d("12"), MAY_1.plusDays(2), null));

            assertThat(valueOf(pen, shop, 3)).isEqualByComparingTo("1260");  // 10 @ 100 + 2 @ 130
            assertThat(valueOf(pen, main, 3)).isEqualByComparingTo("1040");  // 8 @ 130
            assertThat(inventory.valuation(MAY_1.plusDays(2)).total()).isEqualByComparingTo("2300");

            // The shop issues from its own layer: the transferred goods, at their cost.
            sell(pen, shop, 4, "11");
            assertThat(valueOf(pen, shop, 4)).isEqualByComparingTo("130");
        }

        @Test
        @DisplayName("P8-5: a counted gain with no stated cost comes in at the current average")
        void gainAtAverage() {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "4", "25");
            inventory.record(MovementRequest.in(MovementType.ADJUSTMENT_IN, pen.id(), main.id(), d("2"), null,
                    MAY_1.plusDays(1), "Found in count"));
            assertThat(valueOf(pen, main, 2)).isEqualByComparingTo("150");     // 6 @ 25
        }
    }

    @Nested
    @DisplayName("history (D-24, P8-3)")
    class History {

        @Test
        @DisplayName("D-24: value at a past date is what it was then, not today's quantity at old cost")
        void reproducible() {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "10", "100");
            BigDecimal before = valueOf(pen, main, 1);
            sell(pen, main, 5, "8");
            buy(pen, main, 9, "50", "200");

            assertThat(valueOf(pen, main, 1)).isEqualByComparingTo(before).isEqualByComparingTo("1000");
            assertThat(inventory.valuation(MAY_1).lines()).singleElement()
                    .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("10"));
        }

        @Test
        @DisplayName("P8-3: a backdated purchase re-costs every issue after it")
        void backdatedRecosts() {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 2, "10", "100");
            sell(pen, main, 3, "10");
            assertThat(valueOf(pen, main, 3)).isEqualByComparingTo("0");

            // Entered late: a purchase on day 1 at 70. Day 3's sale now takes
            // 10 at the new average of 85, leaving 10 at 85.
            buy(pen, main, 1, "10", "70");

            assertThat(valueOf(pen, main, 2)).isEqualByComparingTo("1700");
            assertThat(valueOf(pen, main, 3)).isEqualByComparingTo("850");
            assertThat(inventory.verifyStock()).isEmpty();
        }
    }

    @Nested
    @DisplayName("valuation runs (P8-4)")
    class Runs {

        @Test
        @DisplayName("posts the closing stock: Stock-in-Hand equals the valuation; gross profit sees the change")
        void posts() throws Exception {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "10", "100");
            sell(pen, main, 3, "4");
            LocalDate end = LocalDate.of(2026, 5, 31);

            ValuationRun run = inventory.postValuation(end);

            assertThat(run.value()).isEqualByComparingTo("600");
            assertThat(run.status()).isEqualTo(ValuationRun.Status.CURRENT);
            assertThat(ledgerNet("Stock-in-Hand", end)).isEqualByComparingTo("600");
            assertThat(ledgerNet("Change in Stock", end)).isEqualByComparingTo("-600");
            Integer affectsGrossProfit = service.readOpenCompany(c -> {
                try (var s = c.createStatement(); var rows = s.executeQuery("SELECT g.affects_gross_profit FROM ledger l "
                        + "JOIN ledger_group g ON g.id = l.group_id WHERE l.system_code = 'CHANGE_IN_STOCK'")) {
                    rows.next();
                    return rows.getInt(1);
                }
            });
            assertThat(affectsGrossProfit).isEqualTo(1);
        }

        @Test
        @DisplayName("a later run posts only the difference; a run for the same date supersedes")
        void incremental() throws Exception {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "10", "100");
            inventory.postValuation(LocalDate.of(2026, 5, 31));
            sell(pen, main, 35, "3");                             // June 4
            inventory.postValuation(LocalDate.of(2026, 6, 30));

            assertThat(ledgerNet("Stock-in-Hand", LocalDate.of(2026, 6, 30))).isEqualByComparingTo("700");

            ValuationRun again = inventory.postValuation(LocalDate.of(2026, 6, 30));
            assertThat(again.voucherId()).as("nothing changed, nothing posted").isNull();
            assertThat(inventory.valuationRuns()).extracting(ValuationRun::status)
                    .containsExactly(ValuationRun.Status.CURRENT, ValuationRun.Status.SUPERSEDED,
                            ValuationRun.Status.CURRENT);
        }

        @Test
        @DisplayName("P8-3: a backdated entry makes posted runs stale; re-posting in order corrects the books")
        void stale() throws Exception {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "10", "100");
            inventory.postValuation(LocalDate.of(2026, 5, 31));
            inventory.postValuation(LocalDate.of(2026, 6, 30));

            buy(pen, main, 20, "5", "40");                        // May 20, entered late

            assertThat(inventory.valuationRuns()).allSatisfy(r -> assertThat(r.status()).isEqualTo(ValuationRun.Status.STALE));
            inventory.postValuation(LocalDate.of(2026, 5, 31));
            assertThat(inventory.valuationRuns().get(0).status()).as("June is stale again after May's correction")
                    .isEqualTo(ValuationRun.Status.STALE);
            inventory.postValuation(LocalDate.of(2026, 6, 30));

            assertThat(ledgerNet("Stock-in-Hand", LocalDate.of(2026, 5, 31))).isEqualByComparingTo("1200");
            assertThat(ledgerNet("Stock-in-Hand", LocalDate.of(2026, 6, 30))).isEqualByComparingTo("1200");
        }

        @Test
        @DisplayName("P8-6: the voucher posts whole paise, half-up; the valuation keeps every digit")
        void roundsToPaise() throws Exception {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "3", "10.005");                   // 30.015
            ValuationRun run = inventory.postValuation(LocalDate.of(2026, 5, 31));

            assertThat(run.value()).isEqualByComparingTo("30.015");
            assertThat(ledgerNet("Stock-in-Hand", LocalDate.of(2026, 5, 31))).isEqualByComparingTo("30.02");
        }

        @Test
        @DisplayName("a READ_ONLY inventory module cannot post a valuation; reading the value still works")
        void entitlement() {
            Item pen = item("Pen", CostingMethod.WEIGHTED_AVERAGE);
            buy(pen, main, 1, "1", "10");
            states.put("inventory", ModuleState.READ_ONLY);

            assertThatThrownBy(() -> inventory.postValuation(LocalDate.of(2026, 5, 31)))
                    .isInstanceOf(ModuleNotWritableException.class);
            assertThat(inventory.valuation(MAY_1).total()).isEqualByComparingTo("10");
        }
    }
}
