package com.accubook.integration;

import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.app.company.CompanyService;
import com.accubook.inventory.Inventory;
import com.accubook.inventory.InventoryModule;
import com.accubook.inventory.InventoryStockPort;
import com.accubook.inventory.Item;
import com.accubook.inventory.ItemKind;
import com.accubook.inventory.MovementRequest;
import com.accubook.inventory.MovementType;
import com.accubook.inventory.Unit;
import com.accubook.inventory.Warehouse;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleNotWritableException;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.purchase.PurchaseBilling;
import com.accubook.purchaseorders.PurchaseOrders;
import com.accubook.sales.SalesInvoicing;
import com.accubook.salesorders.SalesOrders;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.RulePacks;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentStatus;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.LineRequest;
import com.accubook.trade.Order;
import com.accubook.trade.OrderEngine;
import com.accubook.trade.OrderLineRequest;
import com.accubook.trade.OrderRequest;
import com.accubook.trade.OrderStatus;
import com.accubook.trade.Quotation;
import com.accubook.trade.QuotationStatus;
import com.accubook.trade.TradeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Quotations and orders through the real feature modules, with inventory. */
@DisplayName("Quotations and orders")
class OrdersIT {

    private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);

    @TempDir
    Path dir;

    private final Map<String, ModuleState> states = new HashMap<>();
    private CompanyService service;
    private ModuleTransactions transactions;
    private OrderEngine sales;
    private OrderEngine purchase;
    private SalesInvoicing invoicing;
    private PurchaseBilling billing;
    private Inventory inventory;
    private Party customer;
    private Party vendor;
    private Item pen;
    private Warehouse main;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())),
                id -> states.getOrDefault(id, ModuleState.ACTIVE));
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        // P9-8: no document without a GST position. Unregistered: these tests predate GST on documents.
        new GstRegistrations(transactions, Clock.systemUTC()).register(GstRegistration.Scheme.UNREGISTERED, null, "27",
                LocalDate.of(2017, 7, 1), RulePacks.bundledPack());
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);
        Optional<com.accubook.module.stock.StockPort> port = Optional.of(new InventoryStockPort());
        sales = new SalesOrders(transactions, guard, port, clock).quotationsAndOrders();
        purchase = new PurchaseOrders(transactions, guard, port, clock).orders();
        invoicing = new SalesInvoicing(transactions, guard, port, clock);
        billing = new PurchaseBilling(transactions, guard, port, clock);
        inventory = new Inventory(transactions, guard, clock);

        Parties parties = new Parties(transactions);
        customer = parties.create("Mehta Stores", PartyRole.CUSTOMER, null, 0);
        vendor = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);
        Unit nos = inventory.createUnit("NOS", "Numbers", 0);
        pen = inventory.createItem("Gel Pen", ItemKind.GOODS, nos.id(), null, null, null, null);
        main = inventory.createWarehouse("Main", null);
        inventory.record(MovementRequest.in(MovementType.OPENING, pen.id(), main.id(), d("500"), d("8"),
                MAY_1.minusDays(10), null));
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private Order confirmedSalesOrder(String quantity) {
        Order draft = sales.createOrder(OrderRequest.of(customer.id(), MAY_1, MAY_1.plusDays(7),
                List.of(new OrderLineRequest(pen.id(), null, d(quantity), d("12"), 0, main.id()))));
        return sales.confirmOrder(draft.id());
    }

    private Quotation sentQuotation() {
        Quotation q = sales.createQuotation(OrderRequest.of(customer.id(), MAY_1, MAY_1.plusDays(15),
                List.of(OrderLineRequest.of(pen.id(), d("100"), d("12")))));
        return sales.sendQuotation(q.id());
    }

    @Nested
    @DisplayName("quotations (P7-19)")
    class Quotations {

        @Test
        @DisplayName("sent, accepted and converted to a draft order carrying its lines")
        void toOrder() {
            Quotation sent = sentQuotation();
            assertThat(sent.number()).isEqualTo("QT/26-27/00001");
            sales.acceptQuotation(sent.id(), MAY_1.plusDays(2));

            Order order = sales.convertQuotationToOrder(sent.id(), MAY_1.plusDays(3));

            assertThat(order.status()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.quotationId()).isEqualTo(sent.id());
            assertThat(order.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("100"));
            assertThat(sales.findQuotation(sent.id(), MAY_1).orElseThrow().status()).isEqualTo(QuotationStatus.CONVERTED);
            assertThatThrownBy(() -> sales.convertQuotationToOrder(sent.id(), MAY_1.plusDays(3)))
                    .hasMessageContaining("converted");
        }

        @Test
        @DisplayName("expiry is derived; an expired or rejected quotation cannot be converted")
        void expiredAndRejected() {
            Quotation sent = sentQuotation();
            assertThat(sales.findQuotation(sent.id(), MAY_1.plusDays(16)).orElseThrow().status())
                    .isEqualTo(QuotationStatus.EXPIRED);
            assertThatThrownBy(() -> sales.convertQuotationToOrder(sent.id(), MAY_1.plusDays(16)))
                    .hasMessageContaining("expired");
            assertThatThrownBy(() -> sales.acceptQuotation(sent.id(), MAY_1.plusDays(16)))
                    .hasMessageContaining("expired");

            Quotation other = sentQuotation();
            sales.rejectQuotation(other.id(), MAY_1);
            assertThatThrownBy(() -> sales.convertQuotationToOrder(other.id(), MAY_1)).hasMessageContaining("rejected");
        }

        @Test
        @DisplayName("converted straight to an invoice, atomically; an expired one leaves nothing behind")
        void toInvoice() {
            Quotation sent = sentQuotation();
            Document draft = sales.convertQuotationToInvoice(sent.id(), MAY_1.plusDays(1), main.id());
            assertThat(draft.status()).isEqualTo(DocumentStatus.DRAFT);
            assertThat(sales.findQuotation(sent.id(), MAY_1).orElseThrow().status()).isEqualTo(QuotationStatus.CONVERTED);

            Quotation late = sentQuotation();
            int before = invoicing.list().size();
            assertThatThrownBy(() -> sales.convertQuotationToInvoice(late.id(), MAY_1.plusDays(20), main.id()))
                    .isInstanceOf(TradeException.class);
            assertThat(invoicing.list()).hasSize(before);
            assertThat(sales.findQuotation(late.id(), MAY_1).orElseThrow().status()).isEqualTo(QuotationStatus.SENT);
        }
    }

    @Nested
    @DisplayName("sales orders (P7-18, P7-20, R-14)")
    class SalesOrdersTests {

        @Test
        @DisplayName("SO numbered on confirmation; converting and posting fulfils it and moves stock")
        void fulfilled() {
            Order order = confirmedSalesOrder("40");
            assertThat(order.number()).isEqualTo("SO/26-27/00001");
            assertThat(order.status()).isEqualTo(OrderStatus.OPEN);

            Document draft = sales.convertOrder(order.id(), MAY_1.plusDays(2), null);
            invoicing.post(draft.id());

            Order after = sales.findOrder(order.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(OrderStatus.FULFILLED);
            assertThat(after.lines().get(0).fulfilled()).isEqualByComparingTo("40");
            assertThat(inventory.stockOf(pen.id(), main.id())).isEqualByComparingTo("460");
        }

        @Test
        @DisplayName("partial deliveries: each invoice fulfils what it carries; conversion offers only what is open")
        void partial() {
            Order order = confirmedSalesOrder("40");
            String orderLine = order.lines().get(0).id();
            Document first = invoicing.createDraft(DraftRequest.of(com.accubook.trade.DocumentKind.SALES_INVOICE,
                    customer.id(), MAY_1.plusDays(2),
                    List.of(new LineRequest(pen.id(), null, d("15"), d("12"), 0, main.id(), orderLine))));
            invoicing.post(first.id());

            assertThat(sales.findOrder(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIAL);
            Document rest = sales.convertOrder(order.id(), MAY_1.plusDays(4), null);
            assertThat(rest.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("25"));
        }

        @Test
        @DisplayName("P7-20: posting may not fulfil more than is open")
        void noOverFulfilment() {
            Order order = confirmedSalesOrder("40");
            Document a = sales.convertOrder(order.id(), MAY_1.plusDays(2), null);
            Document b = sales.convertOrder(order.id(), MAY_1.plusDays(2), null);
            invoicing.post(a.id());

            assertThatThrownBy(() -> invoicing.post(b.id())).hasMessageContaining("only 0 of \"Gel Pen\" still open");
            assertThat(inventory.stockOf(pen.id(), main.id())).isEqualByComparingTo("460");
        }

        @Test
        @DisplayName("P7-20: a short-closed order fulfils nothing more; a cancelled invoice reopens what it fulfilled")
        void closeAndReopen() {
            Order order = confirmedSalesOrder("40");
            String orderLine = order.lines().get(0).id();
            Document part = invoicing.post(invoicing.createDraft(DraftRequest.of(com.accubook.trade.DocumentKind.SALES_INVOICE,
                    customer.id(), MAY_1.plusDays(2),
                    List.of(new LineRequest(pen.id(), null, d("10"), d("12"), 0, main.id(), orderLine)))).id());

            invoicing.cancel(part.id(), "Wrong customer", MAY_1.plusDays(3));
            assertThat(sales.findOrder(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.OPEN);

            Document pending = sales.convertOrder(order.id(), MAY_1.plusDays(4), null);
            sales.closeOrder(order.id(), "Customer cancelled the rest");
            assertThat(sales.findOrder(order.id()).orElseThrow().status()).isEqualTo(OrderStatus.CLOSED);
            assertThatThrownBy(() -> invoicing.post(pending.id())).hasMessageContaining("can no longer be fulfilled");
        }

        @Test
        @DisplayName("an order can be cancelled only while nothing is fulfilled")
        void cancel() {
            Order fresh = confirmedSalesOrder("5");
            assertThat(sales.cancelOrder(fresh.id(), "Duplicate").status()).isEqualTo(OrderStatus.CANCELLED);

            Order used = confirmedSalesOrder("5");
            invoicing.post(sales.convertOrder(used.id(), MAY_1, null).id());
            assertThatThrownBy(() -> sales.closeOrder(used.id(), " ")).hasMessageContaining("reason");
            assertThatThrownBy(() -> sales.cancelOrder(used.id(), "x")).isInstanceOf(TradeException.class);
        }

        @Test
        @DisplayName("D-11: numbers follow the counter, one series per year")
        void numbering() {
            assertThat(confirmedSalesOrder("1").number()).isEqualTo("SO/26-27/00001");
            sales.createOrder(OrderRequest.of(customer.id(), MAY_1, null, List.of(OrderLineRequest.of(pen.id(), d("1"), d("1")))));
            assertThat(confirmedSalesOrder("1").number()).isEqualTo("SO/26-27/00002");
        }
    }

    @Nested
    @DisplayName("purchase orders and builds")
    class Purchase {

        @Test
        @DisplayName("PO -> bill receives the stock and fulfils the order; partial receipt is possible (R-15)")
        void purchaseOrder() {
            Order po = purchase.confirmOrder(purchase.createOrder(OrderRequest.of(vendor.id(), MAY_1, null,
                    List.of(new OrderLineRequest(pen.id(), null, d("200"), d("7.50"), 0, main.id())))).id());
            assertThat(po.number()).isEqualTo("PO/26-27/00001");

            String line = po.lines().get(0).id();
            billing.post(billing.createDraft(DraftRequest.of(com.accubook.trade.DocumentKind.PURCHASE_BILL,
                    vendor.id(), MAY_1.plusDays(5),
                    List.of(new LineRequest(pen.id(), null, d("120"), d("7.50"), 0, main.id(), line)))).id());

            assertThat(purchase.findOrder(po.id()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIAL);
            assertThat(inventory.stockOf(pen.id(), main.id())).isEqualByComparingTo("620");
        }

        @Test
        @DisplayName("orders need inventory: without a stock port, creating one is refused")
        void needsInventory() {
            EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
            OrderEngine basic = new SalesOrders(transactions, guard, Optional.empty(), Clock.systemUTC())
                    .quotationsAndOrders();
            assertThatThrownBy(() -> basic.createOrder(OrderRequest.of(customer.id(), MAY_1, null,
                    List.of(OrderLineRequest.of(pen.id(), d("1"), d("1")))))).hasMessageContaining("inventory module");
        }

        @Test
        @DisplayName("READ_ONLY sales-orders cannot create; READ_ONLY invoicing cannot invoice from an order")
        void entitlement() {
            Order order = confirmedSalesOrder("5");
            states.put("sales-invoicing", ModuleState.READ_ONLY);
            assertThatThrownBy(() -> sales.convertOrder(order.id(), MAY_1, null))
                    .isInstanceOf(ModuleNotWritableException.class);

            states.put("sales-orders", ModuleState.READ_ONLY);
            assertThatThrownBy(() -> confirmedSalesOrder("1")).isInstanceOf(ModuleNotWritableException.class);
            assertThat(sales.listOrders()).hasSize(1);
        }
    }
}
