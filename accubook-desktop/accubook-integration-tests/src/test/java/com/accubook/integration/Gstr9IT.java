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
import com.accubook.returns.Gstr9;
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

/** P9d-4: GSTR-9 tables 4-9 and 17 from a year with one month of trade, filed. Figures by hand. */
@DisplayName("GSTR-9")
class Gstr9IT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);
    private static final ReturnPeriod MAY = ReturnPeriod.month(2026, 5);
    private static final ReturnPeriod FY = ReturnPeriod.financialYear(2026);
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";
    private static final String CUSTOMER_KA = "29ABCDE1234F1ZW";
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
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T10:00:00Z"), ZoneOffset.UTC);
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

    private Party party(String name, PartyRole role, String gstin) {
        return parties.create(name, role, new PartyProfile(null, null, null, null, null, null, null, null, gstin,
                null, null, null), 0);
    }

    private static LineRequest line(String amount, TaxCategory category, String rate) {
        return LineRequest.text("Goods", d("1"), d(amount)).withTax(category, rate == null ? null : d(rate), null)
                .withHsn("9405");
    }

    /** May: B2B 1,000 (IGST 180), B2C 500 (CGST/SGST 45), exempt 200, a 100 note; a bill and RCM freight. */
    private void mayFiled() {
        Party registered = party("Bengaluru Lights", PartyRole.CUSTOMER, CUSTOMER_KA);
        Party walkIn = party("Cash Customer", PartyRole.CUSTOMER, null);
        Party vendor = party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH);
        Party transporter = party("Speedy Transport", PartyRole.VENDOR, null);
        Document b2b = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, registered.id(),
                MAY_10, List.of(line("1000", TaxCategory.TAXABLE, "18")))).id());
        sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, walkIn.id(), MAY_10,
                List.of(line("500", TaxCategory.TAXABLE, "18"), line("200", TaxCategory.EXEMPT, null)))).id());
        sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, registered.id(), MAY_10, b2b.id(),
                List.of(line("100", TaxCategory.TAXABLE, "18")))).id());
        purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(), MAY_10,
                List.of(line("1000", TaxCategory.TAXABLE, "18"))).withSupplierDocumentNo("P-1")).id());
        purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, transporter.id(), MAY_10,
                List.of(LineRequest.text("Freight", d("1"), d("1000")).withTax(TaxCategory.TAXABLE, d("5"), null)
                        .withHsn("996511"))).withReverseCharge(true)).id());
        returns.importGstr2b("""
                {"data": {"gstin": "27PQRSX5678K1ZY", "rtnprd": "052026", "docdata": {"b2b": [
                  {"ctin": "27LMNOP4321Q1Z4", "inv": [{"inum": "P-1", "dt": "10-05-2026", "val": 1180,
                   "rev": "N", "itcavl": "Y", "items": [{"txval": 1000, "cgst": 90, "sgst": 90}]}]}]}}}
                """);
        returns.markGstr1Filed(MAY, LocalDate.of(2026, 6, 11), null);
        returns.markGstr3bFiled(MAY, LocalDate.of(2026, 6, 20), null);
    }

    @Test
    @DisplayName("tables 4, 5, 6, 8, 9 and 17 from the books and the filed returns")
    void tables() {
        mayFiled();

        Gstr9 r = returns.gstr9(FY);

        assertThat(r.b2b().igst()).isEqualByComparingTo("180");
        assertThat(r.b2c().taxable()).isEqualByComparingTo("500");
        assertThat(r.b2c().cgst()).isEqualByComparingTo("45");
        assertThat(r.creditNotes().igst()).isEqualByComparingTo("18");
        assertThat(r.exempt()).isEqualByComparingTo("200");
        assertThat(r.itcInward().cgst()).isEqualByComparingTo("90");
        assertThat(r.itcReverseCharge().cgst()).isEqualByComparingTo("25");
        assertThat(r.itcAsFiled().cgst()).as("May's 3B: 90 + 25").isEqualByComparingTo("115");
        assertThat(r.itcDifference().cgst()).isEqualByComparingTo("0");
        assertThat(r.itcAs2b().cgst()).isEqualByComparingTo("90");
        assertThat(r.itc2bDifference().cgst()).isEqualByComparingTo("0");
        assertThat(r.taxPayable().igst()).as("180 - 18").isEqualByComparingTo("162");
        assertThat(r.taxPayable().cgst()).as("45 + 25 RCM").isEqualByComparingTo("70");
        assertThat(r.hsn()).filteredOn(h -> h.hsn().equals("9405")).extracting(h -> h.rate().toPlainString(),
                h -> h.taxable().toPlainString()).as("18%: 1,000 + 500 - 100; exempt 200 apart")
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("18", "1400.00"),
                        org.assertj.core.groups.Tuple.tuple("0", "200.00"));
        assertThat(r.gstr1Filed()).isEqualTo(1);
        assertThat(r.issues()).anySatisfy(i -> assertThat(i.message()).contains("1 GSTR-1 and 1 GSTR-3B of 12"));
    }

    @Test
    @DisplayName("filing GSTR-9 locks the whole year")
    void filingLocksTheYear() {
        mayFiled();

        returns.markGstr9Filed(FY, LocalDate.of(2027, 12, 31), null);

        Party walkIn = party("Late Customer", PartyRole.CUSTOMER, null);
        assertThatThrownBy(() -> sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE,
                walkIn.id(), LocalDate.of(2026, 11, 2), List.of(line("100", TaxCategory.TAXABLE, "18")))).id()))
                .hasMessageContaining("GSTR-9 for 2026-04-01 to 2027-03-31 is filed");
    }
}
