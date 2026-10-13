package com.accubook.integration;

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
import com.accubook.returns.GstReturns;
import com.accubook.returns.Gstr3b;
import com.accubook.returns.ReturnPeriod;
import com.accubook.sales.SalesInvoicing;
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

/** P9d-2: GSTR-3B from a month's posted documents. Figures by hand. */
@DisplayName("GSTR-3B")
class Gstr3bIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);
    private static final ReturnPeriod MAY = ReturnPeriod.month(2026, 5);
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";
    private static final String VENDOR_MH = "27LMNOP4321Q1Z4";

    @TempDir
    Path dir;

    private CompanyService service;
    private SalesInvoicing sales;
    private PurchaseBilling purchases;
    private GstReturns returns;
    private Parties parties;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        ModuleTransactions transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.REGULAR, COMPANY_MH, "27", APRIL_1,
                RULES);
        sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        returns = new GstReturns(transactions, guard, () -> RULES, clock);
        parties = new Parties(transactions);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private Party party(String name, PartyRole role, String gstin, String state) {
        return parties.create(name, role, new PartyProfile(null, null, null, null, null, null, state, null, gstin,
                null, null, null), 0);
    }

    private static LineRequest line(String what, String amount, TaxCategory category, String rate) {
        return LineRequest.text(what, d("1"), d(amount)).withTax(category, rate == null ? null : d(rate), null)
                .withHsn("998719");
    }

    @Test
    @DisplayName("outward, inter-state to unregistered, RCM inward, ITC as per books before 2B, exempt inward, cash")
    void month() {
        Party walkIn = party("Cash Customer", PartyRole.CUSTOMER, null, null);
        Party mysore = party("Mysore Buyer", PartyRole.CUSTOMER, null, "29");
        Party vendor = party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null);
        Party transporter = party("Speedy Transport", PartyRole.VENDOR, null, null);

        Document inv = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, walkIn.id(), MAY_10,
                List.of(line("Service", "1000", TaxCategory.TAXABLE, "18"),
                        line("Training", "500", TaxCategory.EXEMPT, null)))).id());          // CGST 90 + SGST 90
        sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, mysore.id(), MAY_10,
                List.of(line("Service", "100", TaxCategory.TAXABLE, "18")))).id());       // IGST 18
        sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, walkIn.id(), MAY_10, inv.id(),
                List.of(line("Service", "200", TaxCategory.TAXABLE, "18")))).id());       // less CGST 18 + SGST 18
        purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(), MAY_10,
                List.of(line("Supplies", "500", TaxCategory.TAXABLE, "18"),
                        line("Fresh produce", "300", TaxCategory.NIL_RATED, null)))).id());  // ITC 45 + 45
        purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, transporter.id(), MAY_10,
                List.of(line("Freight", "1000", TaxCategory.TAXABLE, "5"))).withReverseCharge(true)).id()); // 25 + 25

        Gstr3b r = returns.gstr3b(MAY);

        assertThat(r.outward().taxable()).isEqualByComparingTo("900");
        assertThat(r.outward().igst()).isEqualByComparingTo("18");
        assertThat(r.outward().cgst()).isEqualByComparingTo("72");
        assertThat(r.outward().sgst()).isEqualByComparingTo("72");
        assertThat(r.nilExemptOutward()).isEqualByComparingTo("500");
        assertThat(r.interStateUnregistered()).singleElement().satisfies(u -> {
            assertThat(u.placeOfSupply()).isEqualTo("29");
            assertThat(u.igst()).isEqualByComparingTo("18");
        });
        assertThat(r.reverseChargeInward().taxable()).isEqualByComparingTo("1000");
        assertThat(r.itcReverseCharge().cgst()).isEqualByComparingTo("25");
        assertThat(r.itcOther().cgst()).isEqualByComparingTo("45");
        assertThat(r.exemptInward().exemptNilIntra()).isEqualByComparingTo("300");
        // Liability CGST 72 (+25 RCM). Credit CGST 70 (45 + 25). Forward CGST 72 - 70 = 2, plus RCM 25 in cash.
        assertThat(r.setOff().cash().cgst()).isEqualByComparingTo("27");
        // No IGST credit, and no CGST or SGST credit left over: the 18 IGST is paid in cash.
        assertThat(r.setOff().cash().igst()).isEqualByComparingTo("18");
        assertThat(returns.gstr3bJson(MAY)).contains("\"ret_period\":\"052026\"", "\"osup_det\":{\"txval\":900.00",
                "\"ty\":\"ISRC\",\"iamt\":0.00,\"camt\":25.00", "\"unreg_details\":[{\"pos\":\"29\"");
    }

    @Test
    @DisplayName("P9-14: a filed GSTR-3B locks the period's purchases as well as its sales")
    void filingLocksPurchases() {
        Party vendor = party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null);
        purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(), MAY_10,
                List.of(line("Supplies", "500", TaxCategory.TAXABLE, "18")))).id());

        returns.importGstr2b("{\"data\":{\"gstin\":\"" + COMPANY_MH + "\",\"rtnprd\":\"052026\",\"docdata\":{}}}");
        returns.markGstr3bFiled(MAY, LocalDate.of(2026, 6, 20), null);

        assertThatThrownBy(() -> purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                vendor.id(), LocalDate.of(2026, 5, 31), List.of(line("Late bill", "100", TaxCategory.TAXABLE, "18"))))
                .id())).hasMessageContaining("GSTR-3B for 2026-05-01 to 2026-05-31 is filed");
    }
}
