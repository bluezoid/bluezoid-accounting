package com.accubook.integration;

import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyProfile;
import com.accubook.accounting.party.PartyRole;
import com.accubook.app.company.CompanyService;
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
import com.accubook.tax.GstComponent;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.LineTax;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePacks;
import com.accubook.tax.SupplyType;
import com.accubook.tax.TaxCategory;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.LineRequest;
import com.accubook.trade.TradeException;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * P9b: GST on invoices, bills and notes, posted to per-component ledgers.
 * Every figure is worked out by hand in the test.
 */
@DisplayName("GST on documents")
class GstDocumentsIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);
    private static final LocalDate JUNE_1 = LocalDate.of(2026, 6, 1);
    private static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);

    // Synthetic GSTINs with correct check characters - no real taxpayer's number.
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";
    private static final String CUSTOMER_KA = "29ABCDE1234F1ZW";
    private static final String VENDOR_MH = "27LMNOP4321Q1Z4";

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private GstRegistrations registrations;
    private SalesInvoicing sales;
    private PurchaseBilling purchases;
    private Inventory inventory;
    private Parties parties;
    private Unit nos;
    private Warehouse main;
    private Item lamp;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())),
                id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        registrations = new GstRegistrations(transactions, clock);
        sales = new SalesInvoicing(transactions, guard, Optional.of(new InventoryStockPort()), () -> RULES, clock);
        purchases = new PurchaseBilling(transactions, guard, Optional.of(new InventoryStockPort()), () -> RULES, clock);
        inventory = new Inventory(transactions, guard, clock);
        parties = new Parties(transactions);
        nos = inventory.createUnit("NOS", "Numbers", 0);
        main = inventory.createWarehouse("Main", null);
        lamp = inventory.createItem("Lamp", ItemKind.GOODS, nos.id(), null, null, "9405", null);
        inventory.setTax(lamp.id(), APRIL_1, TaxCategory.TAXABLE, d("18"), null, RULES);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private void regular() {
        registrations.register(GstRegistration.Scheme.REGULAR, COMPANY_MH, "27", APRIL_1, RULES);
    }

    private void compositionScheme() {
        registrations.register(GstRegistration.Scheme.COMPOSITION, COMPANY_MH, "27", APRIL_1, RULES);
    }

    private Party party(String name, PartyRole role, String gstin, String state) {
        return parties.create(name, role, new PartyProfile(null, null, null, null, null, null, state, null, gstin,
                null, null, null), 0);
    }

    private Document stock(Party vendor, String qty, String rate) {
        return purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(),
                MAY_10, List.of(LineRequest.goods(lamp.id(), main.id(), d(qty), d(rate))))).id());
    }

    private Document invoice(Party customer, LocalDate date, String qty, String rate) {
        return sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), date,
                List.of(LineRequest.goods(lamp.id(), main.id(), d(qty), d(rate))))).id());
    }

    /** Debit minus credit on a ledger, from the trial balance, which must balance. */
    private BigDecimal net(String ledger) throws Exception {
        TrialBalance tb = service.readOpenCompany(c -> TrialBalanceQuery.compute(c,
                TrialBalanceQuery.defaultYear(TrialBalanceQuery.fiscalYears(c), MAY_10).orElseThrow().id()));
        assertThat(tb.balances()).isTrue();
        return tb.rows().stream().filter(r -> r.ledgerName().equals(ledger)).findFirst()
                .map(r -> r.debit().subtract(r.credit())).orElse(BigDecimal.ZERO);
    }

    private BigDecimal stockValue(LocalDate on) {
        return inventory.valuation(on).lines().stream().filter(l -> l.itemId().equals(lamp.id()))
                .map(ValuationLine::value).findFirst().orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("P9-8: no document while the company has no GST registration on record")
    void noRegistrationNoDocument() {
        Party customer = party("Mehta Stores", PartyRole.CUSTOMER, null, null);

        assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Service", d("1"), d("100"))))))
                .isInstanceOf(TradeException.class)
                .hasMessageContaining("Record the company's GST registration");
    }

    @Test
    @DisplayName("inter-state sale to a registered customer: IGST 18%, posted to Output IGST")
    void interStateSale() throws Exception {
        regular();
        Party vendor = party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null);
        stock(vendor, "20", "60");
        Party customer = party("Bengaluru Lights", PartyRole.CUSTOMER, CUSTOMER_KA, "27");

        Document inv = invoice(customer, MAY_10, "10", "100");

        assertThat(inv.gst().supplyType()).isEqualTo(SupplyType.INTER_STATE);
        assertThat(inv.gst().placeOfSupply()).as("from the GSTIN, not the recorded state").isEqualTo("29");
        assertThat(inv.gst().partyGstin()).isEqualTo(CUSTOMER_KA);
        assertThat(inv.taxable()).isEqualByComparingTo("1000");
        assertThat(inv.tax()).isEqualByComparingTo("180");
        assertThat(inv.total()).isEqualByComparingTo("1180");
        assertThat(inv.lines().get(0).taxes()).containsExactly(new LineTax(GstComponent.IGST, d("18"), d("180.00")));
        assertThat(inv.lines().get(0).hsnSac()).isEqualTo("9405");

        assertThat(net("Sales")).isEqualByComparingTo("-1000");
        assertThat(net("Output IGST")).isEqualByComparingTo("-180");
        assertThat(net("Bengaluru Lights")).isEqualByComparingTo("1180");
    }

    @Test
    @DisplayName("P9-5: a counter sale with no state is intra-state - CGST 9% + SGST 9%")
    void counterSale() throws Exception {
        regular();
        stock(party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null), "20", "60");
        Party walkIn = party("Cash Customer", PartyRole.CUSTOMER, null, null);

        Document inv = invoice(walkIn, MAY_10, "3", "33.33");      // 99.99

        // 9% of 99.99 = 8.9991 -> 9.00 each, half-up on its own (B-4)
        assertThat(inv.lines().get(0).taxes()).extracting(LineTax::component, LineTax::amount)
                .containsExactly(tuple(GstComponent.CGST, d("9.00")), tuple(GstComponent.SGST, d("9.00")));
        assertThat(inv.amount()).isEqualByComparingTo("117.99");
        assertThat(inv.total()).as("round off on the tax-inclusive total").isEqualByComparingTo("118");
        assertThat(net("Output CGST")).isEqualByComparingTo("-9");
        assertThat(net("Output SGST")).isEqualByComparingTo("-9");
        assertThat(net("Round Off")).isEqualByComparingTo("-0.01");
    }

    @Test
    @DisplayName("purchase from a registered vendor: input CGST + SGST; stock at the taxable cost")
    void regularPurchase() throws Exception {
        regular();
        Party vendor = party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null);

        Document bill = stock(vendor, "10", "50");                    // 500 + 45 + 45

        assertThat(bill.total()).isEqualByComparingTo("590");
        assertThat(net("Purchases")).isEqualByComparingTo("500");
        assertThat(net("Input CGST")).isEqualByComparingTo("45");
        assertThat(net("Input SGST")).isEqualByComparingTo("45");
        assertThat(net("Rao Wholesale")).isEqualByComparingTo("-590");
        assertThat(stockValue(MAY_10)).as("creditable tax is not cost").isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("reverse charge: the vendor is owed the taxable value; the tax is payable and credited")
    void reverseCharge() throws Exception {
        regular();
        Party transporter = party("Speedy Transport", PartyRole.VENDOR, null, null);

        Document bill = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                transporter.id(), MAY_10, List.of(LineRequest.text("Freight", d("1"), d("1000"))
                        .withTax(TaxCategory.TAXABLE, d("5"), null))).withReverseCharge(true)).id());

        assertThat(bill.total()).isEqualByComparingTo("1000");
        assertThat(bill.tax()).isEqualByComparingTo("50");
        assertThat(net("Speedy Transport")).isEqualByComparingTo("-1000");
        assertThat(net("Input CGST")).isEqualByComparingTo("25");
        assertThat(net("RCM Payable CGST")).isEqualByComparingTo("-25");
        assertThat(net("RCM Payable SGST")).isEqualByComparingTo("-25");
    }

    @Test
    @DisplayName("an unregistered vendor charges no GST - unless the bill is reverse charge")
    void unregisteredVendor() throws Exception {
        regular();
        Party local = party("Local Supplier", PartyRole.VENDOR, null, null);

        assertThatThrownBy(() -> purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, local.id(), MAY_10,
                List.of(LineRequest.text("Packing", d("1"), d("100")).withTax(TaxCategory.TAXABLE, d("18"), null)))))
                .hasMessageContaining("has no GSTIN, so it charges no GST");
        Document bill = stock(local, "10", "50");
        assertThat(bill.tax()).isEqualByComparingTo("0");
        assertThat(bill.total()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("a credit note reverses tax at the invoice's rate, even after the item's rate changed")
    void creditNoteAtOriginalRate() throws Exception {
        regular();
        stock(party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null), "20", "60");
        Party customer = party("Bengaluru Lights", PartyRole.CUSTOMER, CUSTOMER_KA, null);
        Document inv = invoice(customer, MAY_10, "10", "100");          // IGST 18% = 180
        // The item's rate changes from June, and even its April entry is corrected afterwards.
        inventory.setTax(lamp.id(), JUNE_1, TaxCategory.TAXABLE, d("5"), null, RULES);
        inventory.setTax(lamp.id(), APRIL_1, TaxCategory.TAXABLE, d("5"), null, RULES);

        Document note = sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(),
                JUNE_10, inv.id(), List.of(LineRequest.goods(lamp.id(), main.id(), d("2"), d("100"))))).id());

        assertThat(note.gst().placeOfSupply()).isEqualTo("29");
        assertThat(note.lines().get(0).taxes()).containsExactly(new LineTax(GstComponent.IGST, d("18"), d("36.00")));
        assertThat(note.total()).isEqualByComparingTo("236");
        assertThat(net("Output IGST")).isEqualByComparingTo("-144");
        assertThat(net("Bengaluru Lights")).isEqualByComparingTo("944");
        assertThatThrownBy(() -> sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(),
                JUNE_10, inv.id(), List.of(LineRequest.goods(lamp.id(), main.id(), d("1"), d("100"))))
                .withPlaceOfSupply("27"))).hasMessageContaining("keeps the place of supply");
    }

    @Test
    @DisplayName("cancelling an invoice brings every GST ledger back to nil")
    void cancellationReversesTax() throws Exception {
        regular();
        stock(party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null), "20", "60");
        Document inv = invoice(party("Cash Customer", PartyRole.CUSTOMER, null, null), MAY_10, "2", "100");

        sales.cancel(inv.id(), "Raised in error", MAY_10);

        assertThat(net("Output CGST")).isEqualByComparingTo("0");
        assertThat(net("Output SGST")).isEqualByComparingTo("0");
        assertThat(net("Sales")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("composition: a bill of supply without GST; no inter-state sale; purchase GST is cost")
    void composition() throws Exception {
        compositionScheme();
        Party vendor = party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null);
        stock(vendor, "10", "50");                                   // 500 + 90 GST, all cost

        assertThat(net("Purchases")).isEqualByComparingTo("590");
        assertThat(net("Input CGST")).isEqualByComparingTo("0");
        assertThat(stockValue(MAY_10)).as("non-creditable GST is cost (AS 2)").isEqualByComparingTo("590");

        Document inv = invoice(party("Cash Customer", PartyRole.CUSTOMER, null, null), MAY_10, "2", "100");
        assertThat(inv.tax()).isEqualByComparingTo("0");
        assertThat(inv.total()).isEqualByComparingTo("200");
        assertThat(inv.lines().get(0).taxes()).isEmpty();

        Party outOfState = party("Bengaluru Lights", PartyRole.CUSTOMER, CUSTOMER_KA, null);
        assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, outOfState.id(),
                MAY_10, List.of(LineRequest.goods(lamp.id(), main.id(), d("1"), d("100"))))))
                .hasMessageContaining("composition dealer cannot supply to another state");
    }

    @Test
    @DisplayName("P9-2: a taxed line needs a category - from the item, or stated on a free-text line")
    void categoryRequired() {
        regular();
        Party customer = party("Cash Customer", PartyRole.CUSTOMER, null, null);
        Item untaxed = inventory.createItem("Shade", ItemKind.SERVICE, nos.id(), null, null, null, null);

        assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Installation", d("1"), d("500"))))))
                .hasMessageContaining("Line 1 (Installation) needs its GST category");
        assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(new LineRequest(untaxed.id(), null, d("1"), d("500"), 0, null)))))
                .hasMessageContaining("Record the item's GST");
        assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Installation", d("1"), d("500")).withTax(TaxCategory.TAXABLE, d("12"), null)))))
                .as("12% left the slabs on 22 Sep 2025").hasMessageContaining("12% is not a GST rate");

        Document exempt = sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Training", d("1"), d("500")).withTax(TaxCategory.EXEMPT, null, null))));
        assertThat(exempt.tax()).isEqualByComparingTo("0");
        assertThat(exempt.lines().get(0).taxCategory()).isEqualTo(TaxCategory.EXEMPT);
    }

    @Test
    @DisplayName("a party's invalid GSTIN is refused before it reaches a document")
    void invalidPartyGstin() {
        regular();
        Party bad = party("Typo Traders", PartyRole.CUSTOMER, "29ABCDE1234F1ZX", null);

        assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, bad.id(), MAY_10,
                List.of(LineRequest.text("Service", d("1"), d("100")).withTax(TaxCategory.TAXABLE, d("18"), null)))))
                .hasMessageContaining("invalid GSTIN");
    }

    @Test
    @DisplayName("a chosen place of supply overrides the party's")
    void chosenPlace() {
        regular();
        Party customer = party("Bengaluru Lights", PartyRole.CUSTOMER, CUSTOMER_KA, null);

        Document draft = sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Hall hire in Pune", d("1"), d("1000"))
                        .withTax(TaxCategory.TAXABLE, d("18"), null))).withPlaceOfSupply("27"));

        assertThat(draft.gst().supplyType()).isEqualTo(SupplyType.INTRA_STATE);
        assertThat(draft.lines().get(0).taxes()).extracting(LineTax::component)
                .containsExactly(GstComponent.CGST, GstComponent.SGST);
    }

    @Test
    @DisplayName("a draft whose GST position changed since it was saved is not posted until saved again")
    void basisChangedSinceSaved() {
        regular();
        Party customer = party("Cash Customer", PartyRole.CUSTOMER, null, null);
        DraftRequest request = DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), JUNE_10,
                List.of(LineRequest.text("Service", d("1"), d("1000")).withTax(TaxCategory.TAXABLE, d("18"), null)));
        Document draft = sales.createDraft(request);
        assertThat(draft.tax()).isEqualByComparingTo("180");

        registrations.register(GstRegistration.Scheme.COMPOSITION, COMPANY_MH, "27", JUNE_1, RULES);

        assertThatThrownBy(() -> sales.post(draft.id())).hasMessageContaining("Save it again");
        Document resaved = sales.updateDraft(draft.id(), DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                JUNE_10, List.of(LineRequest.text("Service", d("1"), d("1000")))));
        Document posted = sales.post(resaved.id());
        assertThat(posted.tax()).as("now a bill of supply").isEqualByComparingTo("0");
        assertThat(posted.total()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a union territory without a legislature charges UTGST, to its own ledger")
    void unionTerritory() throws Exception {
        registrations.register(GstRegistration.Scheme.REGULAR, "04QRSTU6789V1ZB", "04", APRIL_1, RULES);
        Party customer = party("Sector 17 Store", PartyRole.CUSTOMER, null, null);

        Document inv = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Service", d("1"), d("1000")).withTax(TaxCategory.TAXABLE, d("18"), null))))
                .id());

        assertThat(inv.lines().get(0).taxes()).extracting(LineTax::component)
                .containsExactly(GstComponent.CGST, GstComponent.UTGST);
        assertThat(net("Output UTGST")).isEqualByComparingTo("-90");
        assertThat(net("Output SGST")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("composition is refused when last year's turnover exceeds 1.5 crore, allowed once a note brings it down")
    void compositionEligibility() {
        regular();
        Party customer = party("Big Buyer", PartyRole.CUSTOMER, null, null);
        Document inv = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Training", d("1"), d("15000001")).withTax(TaxCategory.EXEMPT, null, null))))
                .id());
        LocalDate nextYear = LocalDate.of(2027, 4, 1);

        assertThatThrownBy(() -> registrations.register(GstRegistration.Scheme.COMPOSITION, COMPANY_MH, "27",
                nextYear, RULES)).hasMessageContaining("above the limit of 15000000");

        sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), JUNE_10, inv.id(),
                List.of(LineRequest.text("Training", d("1"), d("1")).withTax(TaxCategory.EXEMPT, null, null)))).id());
        registrations.register(GstRegistration.Scheme.COMPOSITION, COMPANY_MH, "27", nextYear, RULES);
        assertThat(registrations.on(nextYear).orElseThrow().scheme()).isEqualTo(GstRegistration.Scheme.COMPOSITION);
    }
}
