package com.accubook.trade;

import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.accounting.chart.TrialBalanceRow;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyProfile;
import com.accubook.accounting.party.PartyRole;
import com.accubook.accounting.posting.TransactionalPosting;
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
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleNotWritableException;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.persistence.connection.Database;

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

/**
 * Sales and purchase documents on a real company: shipped migrations, guard
 * installed, inventory as the stock port, the sealed core posting.
 */
@DisplayName("Document engine")
class DocumentEngineIT {

    private static final LocalDate DAY = LocalDate.of(2026, 5, 10);
    private static final ModuleId SALES = ModuleId.of("sales-invoicing");
    private static final ModuleId PURCHASE = ModuleId.of("purchase-billing");

    @TempDir
    Path dir;

    private final Map<String, ModuleState> states = new HashMap<>();
    private CompanyService service;
    private Database second;
    private ModuleTransactions transactions;
    private DocumentEngine sales;
    private DocumentEngine purchases;
    private Inventory inventory;

    private Party customer;
    private Party vendor;
    private Item rice;
    private Warehouse main;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())),
                id -> states.getOrDefault(id, ModuleState.ACTIVE));
        String companyId = service.createCompany("Asha Traders").id();
        service.openCompany(companyId);
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        sales = new DocumentEngine(DocumentKind.Side.SALES, SALES, transactions, guard,
                new TransactionalPosting(guard, clock), Optional.of(new InventoryStockPort()), clock);
        purchases = new DocumentEngine(DocumentKind.Side.PURCHASE, PURCHASE, transactions, guard,
                new TransactionalPosting(guard, clock), Optional.of(new InventoryStockPort()), clock);
        inventory = new Inventory(transactions, guard, clock);

        // Parties are kernel and need a Database; a second handle on the same file.
        second = Database.open(dir.resolve("companies").resolve(companyId).resolve("company.db"));
        Parties parties = new Parties(second);
        customer = parties.create("Mehta Stores", PartyRole.CUSTOMER,
                new PartyProfile(null, null, null, null, null, null, null, null, null, null, null, 30), 0);
        vendor = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);

        Unit kg = inventory.createUnit("KG", "Kilograms", 3);
        rice = inventory.createItem("Basmati Rice", ItemKind.GOODS, kg.id(), null, null, "1006", null);
        main = inventory.createWarehouse("Main", null);
        inventory.record(MovementRequest.in(MovementType.OPENING, rice.id(), main.id(), new BigDecimal("100"),
                new BigDecimal("80"), DAY.minusDays(30), "Opening"));
    }

    @AfterEach
    void tearDown() throws Exception {
        second.close();
        service.close();
    }

    // -------------------------------------------------------------- helpers

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private Document invoice(String quantity, String rate) {
        return sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), DAY,
                List.of(LineRequest.goods(rice.id(), main.id(), d(quantity), d(rate)))));
    }

    private TrialBalance trialBalance() throws SQLException {
        return service.readOpenCompany(connection -> TrialBalanceQuery.compute(connection,
                TrialBalanceQuery.defaultYear(TrialBalanceQuery.fiscalYears(connection), DAY).orElseThrow().id()));
    }

    /** Net debit (positive) or credit (negative) of a ledger in the trial balance. */
    private BigDecimal net(String ledgerName) throws SQLException {
        return trialBalance().rows().stream().filter(r -> r.ledgerName().equals(ledgerName)).findFirst()
                .map(r -> r.debit().subtract(r.credit())).orElse(BigDecimal.ZERO);
    }

    private long count(String sql) throws SQLException {
        return transactions.read(connection -> {
            try (Statement s = connection.createStatement(); var rows = s.executeQuery(sql)) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }

    // ---------------------------------------------------------------- tests

    @Nested
    @DisplayName("sales invoice")
    class SalesInvoice {

        @Test
        @DisplayName("posts revenue, receivable and round off; issues stock; numbers INV/26-27/00001")
        void postsEverything() throws SQLException {
            Document draft = invoice("12.5", "79.90");          // 998.75 -> 999.00

            assertThat(draft.number()).isNull();
            assertThat(draft.amount()).isEqualByComparingTo("998.75");
            assertThat(draft.total()).isEqualByComparingTo("999.00");
            assertThat(draft.dueDate()).isEqualTo(DAY.plusDays(30));

            Document posted = sales.post(draft.id());

            assertThat(posted.status()).isEqualTo(DocumentStatus.POSTED);
            assertThat(posted.number()).isEqualTo("INV/26-27/00001");
            assertThat(net("Mehta Stores")).isEqualByComparingTo("999.00");
            assertThat(net("Sales")).isEqualByComparingTo("-998.75");
            assertThat(net("Round Off")).isEqualByComparingTo("-0.25");
            assertThat(trialBalance().balances()).isTrue();
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("87.5");
            assertThat(inventory.verifyStock()).isEmpty();
        }

        @Test
        @DisplayName("P7-8: an abandoned draft leaves no gap; numbers follow posting order")
        void noGaps() {
            Document abandoned = invoice("1", "10");
            Document first = invoice("1", "10");
            sales.deleteDraft(abandoned.id());
            Document second = invoice("1", "10");

            assertThat(sales.post(second.id()).number()).isEqualTo("INV/26-27/00001");
            assertThat(sales.post(first.id()).number()).isEqualTo("INV/26-27/00002");
        }

        @Test
        @DisplayName("R-9: the series is the fiscal year of the document date, not of today")
        void seriesByDocumentDate() throws SQLException {
            transactions.inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                            + "VALUES ('fy25', '2025-26', '2025-04-01', '2026-03-31', 0)");
                }
            });
            Document march = sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                    LocalDate.of(2026, 3, 31), List.of(LineRequest.text("Consulting", d("1"), d("500")))));

            assertThat(sales.post(march.id()).number()).isEqualTo("INV/25-26/00001");
            assertThat(sales.post(invoice("1", "10").id()).number()).isEqualTo("INV/26-27/00001");
        }

        @Test
        @DisplayName("a date with no fiscal year is refused")
        void noFiscalYear() {
            Document future = sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                    LocalDate.of(2031, 1, 1), List.of(LineRequest.text("x", d("1"), d("1")))));
            assertThatThrownBy(() -> sales.post(future.id())).hasMessageContaining("No fiscal year covers");
        }

        @Test
        @DisplayName("P7-4/P7-5: an invoice beyond stock is not posted at all - no voucher, no number used")
        void stockRefusalUndoesEverything() throws SQLException {
            Document tooMuch = invoice("100.001", "10");

            assertThatThrownBy(() -> sales.post(tooMuch.id())).hasMessageContaining("below zero");

            assertThat(sales.find(tooMuch.id()).orElseThrow().status()).isEqualTo(DocumentStatus.DRAFT);
            assertThat(count("SELECT COUNT(*) FROM voucher")).isZero();
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("100");
            assertThat(sales.post(invoice("1", "10").id()).number()).isEqualTo("INV/26-27/00001");
        }

        @Test
        @DisplayName("P7-10: round off can be switched off per document")
        void roundOffSwitch() throws SQLException {
            Document exact = sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), DAY,
                    List.of(LineRequest.text("Consulting", d("1"), d("99.60")))).withRoundOff(false));
            sales.post(exact.id());

            assertThat(net("Mehta Stores")).isEqualByComparingTo("99.60");
            assertThat(net("Round Off")).isZero();
        }

        @Test
        @DisplayName("a posted invoice cannot be edited, re-posted or deleted")
        void postedIsFinal() {
            Document posted = sales.post(invoice("1", "10").id());
            DraftRequest edit = DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), DAY,
                    List.of(LineRequest.text("x", d("1"), d("1"))));

            assertThatThrownBy(() -> sales.updateDraft(posted.id(), edit)).hasMessageContaining("cancel it instead");
            assertThatThrownBy(() -> sales.post(posted.id())).isInstanceOf(TradeException.class);
            assertThatThrownBy(() -> sales.deleteDraft(posted.id())).isInstanceOf(TradeException.class);
        }

        @Test
        @DisplayName("refuses a vendor-only party, an empty document, and goods without a warehouse")
        void validation() {
            assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, vendor.id(), DAY,
                    List.of(LineRequest.text("x", d("1"), d("1")))))).hasMessageContaining("vendor only");
            assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                    DAY, List.of()))).hasMessageContaining("at least one line");
            assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                    DAY, List.of(LineRequest.goods(rice.id(), null, d("1"), d("1"))))))
                    .hasMessageContaining("warehouse");
            assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                    DAY, List.of(LineRequest.goods(rice.id(), main.id(), d("1.0001"), d("1"))))))
                    .hasMessageContaining("3 decimal places");
        }
    }

    @Nested
    @DisplayName("credit notes (D-23, P7-11)")
    class CreditNotes {

        @Test
        @DisplayName("a credit note reduces revenue and receivables, and returns stock")
        void reduces() throws SQLException {
            Document inv = sales.post(invoice("10", "100").id());
            Document note = sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), DAY,
                    inv.id(), List.of(LineRequest.goods(rice.id(), main.id(), d("4"), d("100")))));

            Document posted = sales.post(note.id());

            assertThat(posted.number()).isEqualTo("CN/26-27/00001");
            assertThat(net("Mehta Stores")).isEqualByComparingTo("600.00");
            assertThat(net("Sales")).isEqualByComparingTo("-600.00");
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("94");
            assertThat(sales.uncredited(inv.id())).isEqualByComparingTo("600.00");
            assertThat(trialBalance().balances()).isTrue();
        }

        @Test
        @DisplayName("notes may not exceed what remains of the original")
        void limited() {
            Document inv = sales.post(invoice("10", "100").id());
            sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), DAY, inv.id(),
                    List.of(LineRequest.text("Rate difference", d("1"), d("700"))))).id());
            Document tooMuch = sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), DAY,
                    inv.id(), List.of(LineRequest.text("Rate difference", d("1"), d("300.01")))).withRoundOff(false));

            assertThatThrownBy(() -> sales.post(tooMuch.id())).hasMessageContaining("exceeds what remains");
        }

        @Test
        @DisplayName("a note needs a posted original of the same party")
        void needsPostedOriginal() {
            Document draftInvoice = invoice("1", "10");
            assertThatThrownBy(() -> sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), DAY,
                    draftInvoice.id(), List.of(LineRequest.text("x", d("1"), d("1"))))))
                    .hasMessageContaining("posted sales invoice");
            assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.CREDIT_NOTE, customer.id(), DAY,
                    List.of(LineRequest.text("x", d("1"), d("1")))))).hasMessageContaining("must name");
        }
    }

    @Nested
    @DisplayName("purchases")
    class Purchases {

        @Test
        @DisplayName("a bill debits Purchases, credits the vendor, receives stock with its cost")
        void bill() throws SQLException {
            Document bill = purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(), DAY,
                    List.of(LineRequest.goods(rice.id(), main.id(), d("50"), d("82.50"))))
                    .withSupplierDocumentNo("RW/1043"));

            Document posted = purchases.post(bill.id());

            assertThat(posted.number()).isEqualTo("PB/26-27/00001");
            assertThat(posted.supplierDocumentNo()).isEqualTo("RW/1043");
            assertThat(net("Purchases")).isEqualByComparingTo("4125.00");
            assertThat(net("Rao Wholesale")).isEqualByComparingTo("-4125.00");
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("150");
            assertThat(inventory.movementsOf(rice.id()).get(1).unitCost()).isEqualByComparingTo("82.50");
        }

        @Test
        @DisplayName("a debit note returns goods to the vendor and reduces what we owe")
        void debitNote() throws SQLException {
            Document bill = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                    vendor.id(), DAY, List.of(LineRequest.goods(rice.id(), main.id(), d("50"), d("80"))))).id());
            purchases.post(purchases.createDraft(DraftRequest.note(DocumentKind.DEBIT_NOTE, vendor.id(), DAY,
                    bill.id(), List.of(LineRequest.goods(rice.id(), main.id(), d("5"), d("80"))))).id());

            assertThat(net("Rao Wholesale")).isEqualByComparingTo("-3600.00");
            assertThat(net("Purchases")).isEqualByComparingTo("3600.00");
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("145");
        }

        @Test
        @DisplayName("a supplier's bill number is refused on a sales document")
        void supplierNumberSalesOnly() {
            assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), DAY,
                    List.of(LineRequest.text("x", d("1"), d("1")))).withSupplierDocumentNo("X")))
                    .hasMessageContaining("purchase document");
        }
    }

    @Nested
    @DisplayName("cancellation (P7-12)")
    class Cancellation {

        @Test
        @DisplayName("reverses the voucher and the stock, keeps the number")
        void cancels() throws SQLException {
            Document inv = sales.post(invoice("10", "100").id());

            Document cancelled = sales.cancel(inv.id(), "Customer refused delivery", DAY.plusDays(1));

            assertThat(cancelled.status()).isEqualTo(DocumentStatus.CANCELLED);
            assertThat(cancelled.number()).isEqualTo("INV/26-27/00001");
            assertThat(net("Mehta Stores")).isZero();
            assertThat(net("Sales")).isZero();
            assertThat(inventory.stockOf(rice.id(), main.id())).isEqualByComparingTo("100");
            assertThat(sales.post(invoice("1", "10").id()).number()).isEqualTo("INV/26-27/00002");
        }

        @Test
        @DisplayName("refused while a note corrects it, and without a reason")
        void refusals() {
            Document inv = sales.post(invoice("10", "100").id());
            sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), DAY, inv.id(),
                    List.of(LineRequest.text("Discount", d("1"), d("50"))))).id());

            assertThatThrownBy(() -> sales.cancel(inv.id(), "x", DAY)).hasMessageContaining("Cancel that note first");
            assertThatThrownBy(() -> sales.cancel(inv.id(), " ", DAY)).hasMessageContaining("reason");
        }
    }

    @Nested
    @DisplayName("builds and entitlement")
    class Builds {

        @Test
        @DisplayName("a build without inventory accepts free-text lines and refuses item lines")
        void withoutInventory() throws SQLException {
            EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
            DocumentEngine basic = new DocumentEngine(DocumentKind.Side.SALES, SALES, transactions, guard,
                    new TransactionalPosting(guard), Optional.empty(), Clock.systemUTC());

            assertThatThrownBy(() -> basic.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                    DAY, List.of(LineRequest.goods(rice.id(), main.id(), d("1"), d("10"))))))
                    .hasMessageContaining("no inventory module");

            basic.post(basic.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), DAY,
                    List.of(LineRequest.text("Consulting", d("2"), d("1500"))))).id());
            assertThat(net("Sales")).isEqualByComparingTo("-3000.00");
        }

        @Test
        @DisplayName("a READ_ONLY sales module cannot create or post; READ_ONLY inventory blocks goods only")
        void entitlement() throws SQLException {
            Document goods = invoice("1", "10");
            Document service_ = sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), DAY,
                    List.of(LineRequest.text("Consulting", d("1"), d("100")))));

            states.put("inventory", ModuleState.READ_ONLY);
            assertThatThrownBy(() -> sales.post(goods.id())).isInstanceOf(ModuleNotWritableException.class);
            sales.post(service_.id());

            states.put("sales-invoicing", ModuleState.READ_ONLY);
            assertThatThrownBy(() -> invoice("1", "10")).isInstanceOf(ModuleNotWritableException.class);
            assertThat(sales.list()).hasSize(2);   // history stays readable
        }

        @Test
        @DisplayName("the trial balance balances after a mixed day's documents")
        void booksBalance() throws SQLException {
            Document inv = sales.post(invoice("3.333", "77.77").id());
            sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), DAY, inv.id(),
                    List.of(LineRequest.goods(rice.id(), main.id(), d("1.111"), d("77.77"))))).id());
            Document bill = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                    vendor.id(), DAY, List.of(new LineRequest(rice.id(), null, d("9.999"), d("81.2345"), 250,
                            main.id())))).id());
            purchases.cancel(bill.id(), "Wrong supplier", DAY);

            TrialBalance tb = trialBalance();
            assertThat(tb.balances()).isTrue();
            assertThat(tb.rows()).extracting(TrialBalanceRow::ledgerName).contains("Mehta Stores", "Sales");
            assertThat(inventory.verifyStock()).isEmpty();
        }
    }
}
