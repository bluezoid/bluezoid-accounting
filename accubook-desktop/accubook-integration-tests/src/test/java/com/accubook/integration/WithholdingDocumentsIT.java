package com.accubook.integration;

import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyProfile;
import com.accubook.accounting.party.PartyRole;
import com.accubook.app.company.CompanyService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.purchase.PurchaseBilling;
import com.accubook.sales.SalesInvoicing;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePacks;
import com.accubook.tax.TaxCategory;
import com.accubook.tax.TrustedRulePackKeys;
import com.accubook.trade.AllocationRequest;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.LineRequest;
import com.accubook.trade.PaymentMode;
import com.accubook.trade.Settlement;
import com.accubook.trade.SettlementRequest;
import com.accubook.trade.SettlementStatus;
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

/**
 * P9c: TDS we deduct on bills, TDS customers deduct on receipts, and TCS on
 * sales (on a test pack - no TCS section is in force). Figures by hand.
 */
@DisplayName("TDS and TCS on documents")
class WithholdingDocumentsIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";
    private static final String VENDOR_MH = "27LMNOP4321Q1Z4";
    private static final String COMPANY_PAN = "LMNCP4321Q";
    private static final String INDIVIDUAL_PAN = "ABCPK1234L";

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private EntitlementGuard guard;
    private Clock clock;
    private SalesInvoicing sales;
    private PurchaseBilling purchases;
    private Parties parties;
    private String cash;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        guard = new EntitlementGuard(service.moduleStates());
        clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.REGULAR, COMPANY_MH, "27", APRIL_1,
                RULES);
        sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        parties = new Parties(transactions);
        cash = service.readOpenCompany(c -> {
            try (var ps = c.prepareStatement("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'");
                 var rows = ps.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private Party party(String name, PartyRole role, String gstin, String pan) {
        return parties.create(name, role, new PartyProfile(null, null, null, null, null, null, null, null, gstin, pan,
                null, null), 0);
    }

    private static LineRequest work(String amount) {
        return LineRequest.text("Civil work", d("1"), d(amount)).withTax(TaxCategory.TAXABLE, d("18"), null);
    }

    private Document bill(Party vendor, LocalDate date, String amount, String section) {
        return purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(), date,
                List.of(work(amount))).withWithholding(section)).id());
    }

    private BigDecimal net(String ledger) throws Exception {
        TrialBalance tb = service.readOpenCompany(c -> TrialBalanceQuery.compute(c,
                TrialBalanceQuery.defaultYear(TrialBalanceQuery.fiscalYears(c), MAY_10).orElseThrow().id()));
        assertThat(tb.balances()).isTrue();
        return tb.rows().stream().filter(r -> r.ledgerName().equals(ledger)).findFirst()
                .map(r -> r.debit().subtract(r.credit())).orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("194C on a company: 2% of the value before GST; the vendor is owed the rest, and a payment of it settles")
    void tdsOnBill() throws Exception {
        Party contractor = party("BuildRight Pvt Ltd", PartyRole.VENDOR, VENDOR_MH, COMPANY_PAN);

        Document b = bill(contractor, MAY_10, "40000", "194C");     // 40,000 + 7,200 GST = 47,200

        assertThat(b.withholdingSection()).isEqualTo("194C-OTH");
        assertThat(b.tds()).isEqualByComparingTo("800");
        assertThat(b.total()).as("the bill's own total is unchanged").isEqualByComparingTo("47200");
        assertThat(b.settle()).isEqualByComparingTo("46400");
        assertThat(net("BuildRight Pvt Ltd")).isEqualByComparingTo("-46400");
        assertThat(net("TDS Payable")).isEqualByComparingTo("-800");

        purchases.settlements().record(SettlementRequest.of(contractor.id(), MAY_10, d("46400"), cash,
                PaymentMode.NEFT, List.of(new AllocationRequest(b.id(), d("46400")))));
        assertThat(purchases.settlements().status(b.id(), MAY_10)).isEqualTo(SettlementStatus.SETTLED);
        assertThat(net("BuildRight Pvt Ltd")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("194C on an individual: 1%; without a PAN: 20%")
    void rateFromPan() {
        Party person = party("Ravi Kumar", PartyRole.VENDOR, null, INDIVIDUAL_PAN);
        Party noPan = party("Unknown Contractor", PartyRole.VENDOR, null, null);

        // An unregistered vendor charges no GST: taxable value only.
        Document a = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, person.id(),
                MAY_10, List.of(LineRequest.text("Labour", d("1"), d("40000")))).withWithholding("194C")).id());
        Document b = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, noPan.id(),
                MAY_10, List.of(LineRequest.text("Labour", d("1"), d("40000")))).withWithholding("194C")).id());

        assertThat(a.withholdingSection()).isEqualTo("194C-IND");
        assertThat(a.tds()).isEqualByComparingTo("400");
        assertThat(b.tds()).isEqualByComparingTo("8000");
    }

    @Test
    @DisplayName("P9-12: the bill that crosses 1 lakh in the year bears TDS on the year's earlier bills too")
    void catchUp() throws Exception {
        Party contractor = party("BuildRight Pvt Ltd", PartyRole.VENDOR, VENDOR_MH, COMPANY_PAN);
        bill(contractor, LocalDate.of(2026, 4, 10), "25000", "194C");
        bill(contractor, LocalDate.of(2026, 4, 20), "25000", "194C");
        Document third = bill(contractor, LocalDate.of(2026, 4, 30), "25000", "194C");
        assertThat(third.tds()).isEqualByComparingTo("0");

        Document crossing = bill(contractor, MAY_10, "26000", "194C");   // 101,000 in the year

        assertThat(crossing.tds()).isEqualByComparingTo("2020");
        Document next = bill(contractor, MAY_10, "5000", "194C");
        assertThat(next.tds()).as("taxed on itself only, now").isEqualByComparingTo("100");
        assertThat(net("TDS Payable")).isEqualByComparingTo("-2120");
    }

    @Test
    @DisplayName("a debit note reverses TDS at the bill's rate on its own value")
    void debitNoteReverses() throws Exception {
        Party contractor = party("BuildRight Pvt Ltd", PartyRole.VENDOR, VENDOR_MH, COMPANY_PAN);
        Document b = bill(contractor, MAY_10, "40000", "194C");

        Document note = purchases.post(purchases.createDraft(DraftRequest.note(DocumentKind.DEBIT_NOTE,
                contractor.id(), MAY_10, b.id(), List.of(work("10000")))).id());

        assertThat(note.withholdingSection()).isEqualTo("194C-OTH");
        assertThat(note.tds()).isEqualByComparingTo("200");                 // 2% of 10,000
        assertThat(note.settle()).isEqualByComparingTo("11600");            // 11,800 - 200
        assertThat(net("TDS Payable")).isEqualByComparingTo("-600");
        assertThat(net("BuildRight Pvt Ltd")).isEqualByComparingTo("-34800"); // 46,400 - 11,600
        assertThat(purchases.settlements().outstanding(b.id(), MAY_10)).isEqualByComparingTo("34800");
    }

    @Test
    @DisplayName("cancelling a bill cancels its TDS, and a later bill no longer counts it towards the year")
    void cancellation() throws Exception {
        Party contractor = party("BuildRight Pvt Ltd", PartyRole.VENDOR, VENDOR_MH, COMPANY_PAN);
        Document big = bill(contractor, LocalDate.of(2026, 4, 10), "90000", "194C");    // TDS 1,800
        purchases.cancel(big.id(), "Entered twice", MAY_10);

        Document small = bill(contractor, MAY_10, "20000", "194C");

        assertThat(net("TDS Payable")).isEqualByComparingTo("0");
        assertThat(small.tds()).as("20,000 is below both limits once the 90,000 is gone").isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("194J from 1 Apr 2025: fees of 50,000 bear no TDS; a section not in force is refused")
    void thresholdsAndSections() {
        Party lawyer = party("Mehta Associates", PartyRole.VENDOR, null, INDIVIDUAL_PAN);
        Document fee = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, lawyer.id(),
                MAY_10, List.of(LineRequest.text("Legal fees", d("1"), d("50000")))).withWithholding("194J-PROF")).id());
        assertThat(fee.tds()).isEqualByComparingTo("0");

        Party customer = party("Bulk Buyer", PartyRole.CUSTOMER, null, COMPANY_PAN);
        assertThatThrownBy(() -> sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(work("100000"))).withWithholding("206C(1H)")))
                .isInstanceOf(TradeException.class).hasMessageContaining("not in force on 2026-05-10");
    }

    @Test
    @DisplayName("an invalid PAN is refused rather than silently charged the no-PAN rate")
    void invalidPan() {
        Party typo = party("Typo Builders", PartyRole.VENDOR, null, "ABCXK1234L");

        assertThatThrownBy(() -> purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, typo.id(), MAY_10,
                List.of(LineRequest.text("Labour", d("1"), d("40000")))).withWithholding("194C")))
                .hasMessageContaining("invalid PAN");
    }

    @Test
    @DisplayName("a customer's TDS on our receipt: the bank gets the rest, TDS Receivable the deduction, the invoice is settled")
    void tdsReceivable() throws Exception {
        Party client = party("Client Ltd", PartyRole.CUSTOMER, null, COMPANY_PAN);
        Document inv = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, client.id(), MAY_10,
                List.of(LineRequest.text("Consulting", d("1"), d("100000"))
                        .withTax(TaxCategory.TAXABLE, d("18"), null)))).id());     // 1,18,000

        Settlement receipt = sales.settlements().record(SettlementRequest.of(client.id(), MAY_10, d("118000"), cash,
                PaymentMode.NEFT, List.of(new AllocationRequest(inv.id(), d("118000")))).withTds(d("10000"), "194J-PROF"));

        assertThat(receipt.tdsDeducted()).isEqualByComparingTo("10000");
        assertThat(net("Cash in Hand")).isEqualByComparingTo("108000");
        assertThat(net("TDS Receivable")).isEqualByComparingTo("10000");
        assertThat(net("Client Ltd")).isEqualByComparingTo("0");
        assertThat(sales.settlements().status(inv.id(), MAY_10)).isEqualTo(SettlementStatus.SETTLED);

        sales.settlements().cancel(receipt.id(), "Bounced", MAY_10);
        assertThat(net("TDS Receivable")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TDS on a receipt needs a section in force, and is never taken on a payment")
    void receiptTdsRules() {
        Party client = party("Client Ltd", PartyRole.CUSTOMER, null, COMPANY_PAN);
        Party vendor = party("Rao Wholesale", PartyRole.VENDOR, null, COMPANY_PAN);

        assertThatThrownBy(() -> sales.settlements().record(SettlementRequest.of(client.id(), MAY_10, d("1000"), cash,
                PaymentMode.NEFT, List.of()).withTds(d("100"), "194Z"))).hasMessageContaining("194Z");
        assertThatThrownBy(() -> purchases.settlements().record(SettlementRequest.of(vendor.id(), MAY_10, d("1000"),
                cash, PaymentMode.NEFT, List.of()).withTds(d("100"), "194C")))
                .hasMessageContaining("deducted on the bill");
        assertThatThrownBy(() -> SettlementRequest.of(client.id(), MAY_10, d("1000"), cash, PaymentMode.NEFT,
                List.of()).withTds(d("1000"), "194J-PROF")).hasMessageContaining("less than");
    }

    @Test
    @DisplayName("TCS (on a test pack with a section in force): the customer is debited total + TCS")
    void tcsOnSale() throws Exception {
        String body = RulePacks.bundled(TrustedRulePackKeys.forThisBuild()).body()
                .replace("[tcs]\n", "[tcs]\nTEST-TCS|2026-04-01||1|5|NONE|||Test section, not law\n");
        RulePack withTcs = RulePack.parse(body);
        SalesInvoicing tcsSales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> withTcs, clock);
        Party customer = party("Bulk Buyer", PartyRole.CUSTOMER, null, COMPANY_PAN);

        Document inv = tcsSales.post(tcsSales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                MAY_10, List.of(work("100000"))).withWithholding("TEST-TCS")).id());   // 1,18,000

        assertThat(inv.tcs()).as("1% of the whole amount, GST included").isEqualByComparingTo("1180");
        assertThat(inv.settle()).isEqualByComparingTo("119180");
        assertThat(net("Bulk Buyer")).isEqualByComparingTo("119180");
        assertThat(net("TCS Payable")).isEqualByComparingTo("-1180");
        assertThat(net("Sales")).isEqualByComparingTo("-100000");
    }
}
