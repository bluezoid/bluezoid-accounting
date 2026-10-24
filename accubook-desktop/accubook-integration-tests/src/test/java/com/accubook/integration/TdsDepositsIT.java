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
import com.accubook.returns.Challan;
import com.accubook.returns.ChallanRequest;
import com.accubook.returns.Form16A;
import com.accubook.returns.Form26Q;
import com.accubook.returns.ReturnPeriod;
import com.accubook.returns.TdsDeduction;
import com.accubook.returns.TdsDeposits;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePacks;
import com.accubook.tax.Tan;
import com.accubook.tax.TaxCategory;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P9d-5: depositing TDS, s.201(1A) interest (P9-23), 26Q data for the RPU (P9-24) and Form 16A. */
@DisplayName("TDS deposits and quarterly returns")
class TdsDepositsIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final ReturnPeriod Q1 = ReturnPeriod.quarter(2026, 4);
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";
    private static final String VENDOR_MH = "27LMNOP4321Q1Z4";

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private PurchaseBilling purchases;
    private TdsDeposits deposits;
    private Parties parties;
    private String cash;
    private Document contract;
    private Document fees;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-07-15T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.REGULAR, COMPANY_MH, "27", APRIL_1,
                RULES);
        purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        deposits = new TdsDeposits(transactions, guard, clock);
        parties = new Parties(transactions);
        cash = service.readOpenCompany(c -> {
            try (var ps = c.prepareStatement("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'");
                 var rows = ps.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        });
        Party builder = party("BuildRight Pvt Ltd", VENDOR_MH, "LMNCP4321Q");
        Party lawyer = party("Mehta Associates", null, "ABCPK1234L");
        contract = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, builder.id(),
                LocalDate.of(2026, 5, 10), List.of(LineRequest.text("Civil work", d("1"), d("40000"))
                        .withTax(TaxCategory.TAXABLE, d("18"), null).withHsn("995411"))).withWithholding("194C"))
                .id());                                                          // TDS 2% = 800
        fees = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, lawyer.id(),
                LocalDate.of(2026, 5, 20), List.of(LineRequest.text("Legal fees", d("1"), d("60000"))))
                .withWithholding("194J-PROF")).id());                           // TDS 10% = 6,000
        purchases.post(purchases.createDraft(DraftRequest.note(DocumentKind.DEBIT_NOTE, builder.id(),
                LocalDate.of(2026, 6, 1), contract.id(), List.of(LineRequest.text("Civil work", d("1"), d("10000"))
                        .withTax(TaxCategory.TAXABLE, d("18"), null).withHsn("995411")))).id());   // reverses 200
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private Party party(String name, String gstin, String pan) {
        return parties.create(name, PartyRole.VENDOR, new PartyProfile(null, null, null, null, null, null, null, null,
                gstin, pan, null, null), 0);
    }

    private TdsDeduction deduction(Document doc) {
        return deposits.deductions("TDS", Q1.from(), Q1.to()).stream()
                .filter(x -> x.documentNumber().equals(doc.number())).findFirst().orElseThrow();
    }

    private BigDecimal net(String ledger) throws Exception {
        TrialBalance tb = service.readOpenCompany(c -> TrialBalanceQuery.compute(c,
                TrialBalanceQuery.defaultYear(TrialBalanceQuery.fiscalYears(c), APRIL_1).orElseThrow().id()));
        assertThat(tb.balances()).isTrue();
        return tb.rows().stream().filter(r -> r.ledgerName().equals(ledger)).findFirst()
                .map(r -> r.debit().subtract(r.credit())).orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("deductions net of notes; a deposit clears TDS Payable through the bank")
    void depositOnTime() throws Exception {
        TdsDeduction c = deduction(contract);
        assertThat(c.amount()).as("800 less 200 reversed by the note").isEqualByComparingTo("600");
        assertThat(c.dueDate()).isEqualTo(LocalDate.of(2026, 6, 7));

        Challan challan = deposits.deposit(new ChallanRequest(LocalDate.of(2026, 6, 7), "0510308", "00042",
                List.of(c.entryId()), null, null, cash));

        assertThat(challan.section()).as("194C-OTH is 194C on the challan").isEqualTo("194C");
        assertThat(challan.tax()).isEqualByComparingTo("600");
        assertThat(net("TDS Payable")).as("6,000 of 194J still owed").isEqualByComparingTo("-6000");
        assertThat(net("Cash in Hand")).isEqualByComparingTo("-600");
        assertThat(deduction(contract).challanId()).isEqualTo(challan.id());
    }

    @Test
    @DisplayName("P9-23: deposited late, s.201(1A) interest is computed; paying less is flagged, not refused")
    void lateDeposit() throws Exception {
        TdsDeduction f = deduction(fees);
        // Deducted 20 May, due 7 June, deposited 10 July: May, June, July = 3 months x 1.5% of 6,000 = 270.
        assertThat(f.interestIfDepositedOn(LocalDate.of(2026, 7, 10))).isEqualByComparingTo("270");

        deposits.deposit(new ChallanRequest(LocalDate.of(2026, 7, 10), "0510308", "00043", List.of(f.entryId()),
                d("200"), null, cash));

        assertThat(net("Interest and Fees on TDS")).isEqualByComparingTo("200");
        Form26Q q = deposits.quarterly("TDS", Q1);
        assertThat(q.issues()).anySatisfy(i -> assertThat(i.message())
                .contains("s.201(1A) interest is 270.00, paid 200.00"));
    }

    @Test
    @DisplayName("26Q: blocked until every deduction is deposited and the TAN is recorded; then complete")
    void quarterly() {
        Form26Q before = deposits.quarterly("TDS", Q1);
        assertThat(before.complete()).isFalse();
        assertThat(before.issues()).extracting(i -> i.message()).anyMatch(m -> m.contains("Record the company's TAN"))
                .anyMatch(m -> m.contains("is not deposited (due 2026-06-07)"));

        new Tan(transactions).set("MUMA12345B");
        deposits.deposit(new ChallanRequest(LocalDate.of(2026, 6, 5), "0510308", "00044",
                List.of(deduction(contract).entryId()), null, null, cash));
        deposits.deposit(new ChallanRequest(LocalDate.of(2026, 6, 6), "0510308", "00045",
                List.of(deduction(fees).entryId()), null, null, cash));

        Form26Q after = deposits.quarterly("TDS", Q1);
        assertThat(after.complete()).isTrue();
        assertThat(after.tan()).isEqualTo("MUMA12345B");
        assertThat(after.challans()).hasSize(2);
        assertThat(deposits.quarterlyJson("TDS", Q1)).contains("\"form\":\"26Q\"", "\"tan\":\"MUMA12345B\"",
                "\"pan\":\"ABCPK1234L\"", "\"section\":\"194J-PROF\"", "\"tax_deducted\":6000.00");
        List<Form16A> certificates = deposits.form16A(Q1);
        assertThat(certificates).hasSize(2);
        assertThat(certificates).filteredOn(f -> f.pan().equals("ABCPK1234L")).singleElement()
                .satisfies(f -> assertThat(f.totalDeducted()).isEqualByComparingTo("6000"));
    }

    @Test
    @DisplayName("one section per challan; a deduction is deposited once; challan numbers are checked")
    void rules() {
        assertThatThrownBy(() -> deposits.deposit(new ChallanRequest(LocalDate.of(2026, 6, 7), "0510308", "00046",
                List.of(deduction(contract).entryId(), deduction(fees).entryId()), null, null, cash)))
                .hasMessageContaining("one section's TDS");
        deposits.deposit(new ChallanRequest(LocalDate.of(2026, 6, 7), "0510308", "00047",
                List.of(deduction(contract).entryId()), null, null, cash));
        assertThatThrownBy(() -> deposits.deposit(new ChallanRequest(LocalDate.of(2026, 6, 8), "0510308", "00048",
                List.of(deduction(contract).entryId()), null, null, cash))).hasMessageContaining("already deposited");
        assertThatThrownBy(() -> new ChallanRequest(LocalDate.of(2026, 6, 8), "05103", "00048", List.of("x"), null,
                null, cash)).hasMessageContaining("BSR code is 7 digits");
    }

    @Test
    @DisplayName("a bill whose TDS is deposited cannot be cancelled; it is corrected with a note")
    void depositedNotCancelled() {
        deposits.deposit(new ChallanRequest(LocalDate.of(2026, 6, 7), "0510308", "00049",
                List.of(deduction(fees).entryId()), null, null, cash));

        assertThatThrownBy(() -> purchases.cancel(fees.id(), "Entered twice", LocalDate.of(2026, 6, 10)))
                .hasMessageContaining("deposited");
    }

    @Test
    @DisplayName("a deduction on 31 March is in Q4, and one on 1 April is not (the reference lost late-evening ones)")
    void quarterBoundaries() {
        Party lawyer = party("Year End Advisors", null, "ABCPK1234L");
        Document march = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                lawyer.id(), LocalDate.of(2027, 3, 31), List.of(LineRequest.text("Audit fees", d("1"), d("60000"))))
                .withWithholding("194J-PROF")).id());

        assertThat(deposits.quarterly("TDS", ReturnPeriod.quarter(2027, 1)).deductions())
                .extracting(TdsDeduction::documentNumber).containsExactly(march.number());
        assertThat(deposits.quarterly("TDS", ReturnPeriod.quarter(2026, 4)).deductions())
                .extracting(TdsDeduction::documentNumber).doesNotContain(march.number());
        assertThat(deposits.deductions("TDS", LocalDate.of(2027, 4, 1), LocalDate.of(2027, 6, 30))).isEmpty();
    }
}
