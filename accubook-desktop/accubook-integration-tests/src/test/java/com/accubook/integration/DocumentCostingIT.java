package com.accubook.integration;

import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.app.company.CompanyService;
import com.accubook.inventory.CostingMethod;
import com.accubook.inventory.Inventory;
import com.accubook.inventory.InventoryModule;
import com.accubook.inventory.InventoryStockPort;
import com.accubook.inventory.Item;
import com.accubook.inventory.ItemKind;
import com.accubook.inventory.Unit;
import com.accubook.inventory.ValuationLine;
import com.accubook.inventory.Warehouse;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.purchase.PurchaseBilling;
import com.accubook.sales.SalesInvoicing;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.LineRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 8 with real documents: returns and cancellations at original cost; the month's books. */
@DisplayName("Costing through documents")
class DocumentCostingIT {

    private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);

    @TempDir
    Path dir;

    private CompanyService service;
    private SalesInvoicing sales;
    private PurchaseBilling purchases;
    private Inventory inventory;
    private Party customer;
    private Party vendor;
    private Unit nos;
    private Warehouse main;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        ModuleTransactions tx = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);
        sales = new SalesInvoicing(tx, guard, Optional.of(new InventoryStockPort()), clock);
        purchases = new PurchaseBilling(tx, guard, Optional.of(new InventoryStockPort()), clock);
        inventory = new Inventory(tx, guard, clock);
        Parties parties = new Parties(tx);
        customer = parties.create("Mehta Stores", PartyRole.CUSTOMER, null, 0);
        vendor = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);
        nos = inventory.createUnit("NOS", "Numbers", 0);
        main = inventory.createWarehouse("Main", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private Item item(CostingMethod method) {
        Item item = inventory.createItem("Lamp " + method, ItemKind.GOODS, nos.id(), null, null, null, null);
        inventory.setCostingMethod(item.id(), method);
        return item;
    }

    private Document bill(Item item, int day, String qty, String rate) {
        return purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(),
                MAY_1.plusDays(day - 1), List.of(LineRequest.goods(item.id(), main.id(), d(qty), d(rate))))).id());
    }

    private Document invoice(Item item, int day, String qty, String rate) {
        return sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                MAY_1.plusDays(day - 1), List.of(LineRequest.goods(item.id(), main.id(), d(qty), d(rate))))).id());
    }

    private BigDecimal value(Item item, int day) {
        return inventory.valuation(MAY_1.plusDays(day - 1)).lines().stream()
                .filter(l -> l.itemId().equals(item.id())).map(ValuationLine::value)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private BigDecimal net(String ledger, LocalDate on) throws Exception {
        TrialBalance tb = service.readOpenCompany(c -> TrialBalanceQuery.compute(c,
                TrialBalanceQuery.defaultYear(TrialBalanceQuery.fiscalYears(c), on).orElseThrow().id()));
        assertThat(tb.balances()).isTrue();
        return tb.rows().stream().filter(r -> r.ledgerName().equals(ledger)).findFirst()
                .map(r -> r.debit().subtract(r.credit())).orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a sales return comes back at the cost it left with, not the average since")
    void salesReturnAtIssueCost() {
        Item lamp = item(CostingMethod.WEIGHTED_AVERAGE);
        bill(lamp, 1, "10", "100");
        Document inv = invoice(lamp, 2, "4", "150");              // issued at 100
        bill(lamp, 3, "6", "200");                                // average now (600 + 1200) / 12 = 150

        sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), MAY_1.plusDays(3),
                inv.id(), List.of(LineRequest.goods(lamp.id(), main.id(), d("2"), d("150"))))).id());

        assertThat(value(lamp, 4)).isEqualByComparingTo("2000");   // 1800 + 2 @ 100
    }

    @Test
    @DisplayName("cancelling an invoice restores its goods at the cost they left with")
    void cancellationAtIssueCost() {
        Item lamp = item(CostingMethod.WEIGHTED_AVERAGE);
        bill(lamp, 1, "10", "100");
        Document inv = invoice(lamp, 2, "4", "150");
        bill(lamp, 3, "6", "200");

        sales.cancel(inv.id(), "Raised in error", MAY_1.plusDays(3));

        assertThat(value(lamp, 4)).isEqualByComparingTo("2200");   // 1800 + 4 @ 100
    }

    @Test
    @DisplayName("FIFO: a purchase return leaves from the layer of the bill it returns")
    void purchaseReturnFromItsLayer() {
        Item lamp = item(CostingMethod.FIFO);
        bill(lamp, 1, "10", "100");
        Document second = bill(lamp, 2, "10", "130");

        purchases.post(purchases.createDraft(DraftRequest.note(DocumentKind.DEBIT_NOTE, vendor.id(), MAY_1.plusDays(2),
                second.id(), List.of(LineRequest.goods(lamp.id(), main.id(), d("3"), d("130"))))).id());

        assertThat(value(lamp, 3)).isEqualByComparingTo("1910");   // 2300 - 3 @ 130
    }

    @Test
    @DisplayName("a month of trade: gross profit is sales - purchases + closing stock, and Stock-in-Hand agrees")
    void monthOfTrade() throws Exception {
        Item lamp = item(CostingMethod.WEIGHTED_AVERAGE);
        bill(lamp, 1, "20", "100");          // purchases 2000
        invoice(lamp, 5, "12", "160");       // sales 1920
        bill(lamp, 10, "10", "120");         // purchases 1200 -> 3200
        invoice(lamp, 20, "6", "170");       // sales 1020 -> 2940
        LocalDate end = LocalDate.of(2026, 5, 31);

        inventory.postValuation(end);

        // 8 left @ 100 after the first sale (800), +10 @ 120 = 2000 for 18 (avg 111.11...),
        // sell 6 -> 12 remain at 2000 x 12/18 = 1333.33
        BigDecimal closing = inventory.valuation(end).total();
        assertThat(closing).isEqualByComparingTo("1333.3333");
        // P8-6: the ledger carries the value rounded to the paisa.
        assertThat(net("Stock-in-Hand", end)).isEqualByComparingTo("1333.33");
        BigDecimal grossProfit = net("Sales", end).negate()
                .subtract(net("Purchases", end))
                .subtract(net("Change in Stock", end));
        assertThat(grossProfit).isEqualByComparingTo(d("2940").subtract(d("3200")).add(d("1333.33")));
    }
}
