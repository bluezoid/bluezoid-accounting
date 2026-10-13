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
import com.accubook.returns.Cmp08;
import com.accubook.returns.FiledReturn;
import com.accubook.returns.GstReturns;
import com.accubook.returns.ReturnPeriod;
import com.accubook.sales.SalesInvoicing;
import com.accubook.tax.CompositionCategory;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePacks;
import com.accubook.tax.TaxCategory;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.LineRequest;

import org.junit.jupiter.api.AfterEach;
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

/** P9d-4: CMP-08 from bills of supply, the composition tax booked on filing (P9-21). Figures by hand. */
@DisplayName("CMP-08")
class Cmp08IT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final ReturnPeriod Q1 = ReturnPeriod.quarter(2026, 4);
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";

    @TempDir
    Path dir;

    private CompanyService service;
    private SalesInvoicing sales;
    private PurchaseBilling purchases;
    private GstReturns returns;
    private Parties parties;

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private void company(GstRegistration.Scheme scheme, CompositionCategory category) throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        ModuleTransactions transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-07-10T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(scheme, COMPANY_MH, "27", APRIL_1, RULES, category);
        sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        returns = new GstReturns(transactions, guard, () -> RULES, clock);
        parties = new Parties(transactions);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static LineRequest line(String what, String amount, TaxCategory category) {
        return LineRequest.text(what, d("1"), d(amount))
                .withTax(category, category == TaxCategory.TAXABLE ? d("18") : null, null);
    }

    /** A quarter: taxable 10,000 + exempt 5,000 in May, taxable 20,000 in June, a 1,000 note, RCM freight. */
    private void quarter() {
        Party customer = parties.create("Cash Customer", PartyRole.CUSTOMER, null, 0);
        Party transporter = parties.create("Speedy Transport", PartyRole.VENDOR, PartyProfile.empty(), 0);
        Document may = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                LocalDate.of(2026, 5, 10), List.of(line("Lamps", "10000", TaxCategory.TAXABLE),
                        line("Books", "5000", TaxCategory.EXEMPT)))).id());
        sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                LocalDate.of(2026, 6, 10), List.of(line("Lamps", "20000", TaxCategory.TAXABLE)))).id());
        sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(),
                LocalDate.of(2026, 6, 20), may.id(), List.of(line("Lamps", "1000", TaxCategory.TAXABLE)))).id());
        purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, transporter.id(),
                LocalDate.of(2026, 6, 5), List.of(LineRequest.text("Freight", d("1"), d("1000"))
                        .withTax(TaxCategory.TAXABLE, d("5"), null))).withReverseCharge(true)).id());
    }

    private BigDecimal net(String ledger) throws Exception {
        TrialBalance tb = service.readOpenCompany(c -> TrialBalanceQuery.compute(c,
                TrialBalanceQuery.defaultYear(TrialBalanceQuery.fiscalYears(c), APRIL_1).orElseThrow().id()));
        assertThat(tb.balances()).isTrue();
        return tb.rows().stream().filter(r -> r.ledgerName().equals(ledger)).findFirst()
                .map(r -> r.debit().subtract(r.credit())).orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a trader pays 1% on taxable supplies only - not on exempt (R-24) - plus reverse-charge tax")
    void trader() throws Exception {
        company(GstRegistration.Scheme.COMPOSITION, CompositionCategory.TRADER);
        quarter();

        Cmp08 r = returns.cmp08(Q1);

        assertThat(r.outwardTurnover()).isEqualByComparingTo("34000");      // 35,000 - 1,000
        assertThat(r.outwardBase()).isEqualByComparingTo("29000");          // taxable only: 10,000 + 20,000 - 1,000
        assertThat(r.compositionCgst()).isEqualByComparingTo("145");
        assertThat(r.compositionSgst()).isEqualByComparingTo("145");
        assertThat(r.reverseCharge().cgst()).isEqualByComparingTo("25");
        assertThat(r.totalPayable()).isEqualByComparingTo("340");
        assertThat(r.issues()).isEmpty();
    }

    @Test
    @DisplayName("a restaurant pays 5% on its whole turnover")
    void restaurant() throws Exception {
        company(GstRegistration.Scheme.COMPOSITION, CompositionCategory.RESTAURANT);
        quarter();

        Cmp08 r = returns.cmp08(Q1);

        assertThat(r.outwardBase()).isEqualByComparingTo("34000");
        assertThat(r.compositionTax()).isEqualByComparingTo("1700");
    }

    @Test
    @DisplayName("P9-21: filing books the composition tax and locks the quarter; unlocking reverses the booking")
    void filingBooksTax() throws Exception {
        company(GstRegistration.Scheme.COMPOSITION, CompositionCategory.TRADER);
        quarter();

        FiledReturn filed = returns.markCmp08Filed(Q1, LocalDate.of(2026, 7, 10), null);

        assertThat(net("Composition Tax")).isEqualByComparingTo("290");
        assertThat(net("Composition Tax Payable")).isEqualByComparingTo("-290");
        Party customer = parties.create("Late Customer", PartyRole.CUSTOMER, null, 0);
        assertThatThrownBy(() -> sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE,
                customer.id(), LocalDate.of(2026, 6, 30), List.of(line("Lamps", "100", TaxCategory.TAXABLE)))).id()))
                .hasMessageContaining("CMP-08");

        returns.unlock(filed.id(), "Turnover understated", LocalDate.of(2026, 7, 15));

        assertThat(net("Composition Tax")).isEqualByComparingTo("0");
        assertThat(returns.filed()).singleElement().satisfies(f -> assertThat(f.unlocked()).isTrue());
    }

    @Test
    @DisplayName("CMP-08 is quarterly and a composition dealer's")
    void onlyCompositionQuarters() throws Exception {
        company(GstRegistration.Scheme.REGULAR, null);

        assertThatThrownBy(() -> returns.cmp08(Q1)).hasMessageContaining("composition dealer's statement");
        assertThatThrownBy(() -> returns.cmp08(ReturnPeriod.month(2026, 5))).hasMessageContaining("quarterly");
    }
}
