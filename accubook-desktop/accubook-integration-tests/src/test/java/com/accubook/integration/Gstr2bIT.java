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
import com.accubook.returns.Gstr2bImported;
import com.accubook.returns.Gstr2bReconciliation;
import com.accubook.returns.Gstr2bReconciliation.Status;
import com.accubook.returns.Gstr3b;
import com.accubook.returns.ReturnException;
import com.accubook.returns.ReturnPeriod;
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

/** P9d-3: GSTR-2B against the purchase books, and GSTR-3B credit only as in 2B (P9-19). Figures by hand. */
@DisplayName("GSTR-2B reconciliation")
class Gstr2bIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final ReturnPeriod MAY = ReturnPeriod.month(2026, 5);
    private static final ReturnPeriod JUNE = ReturnPeriod.month(2026, 6);
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";
    private static final String VENDOR_MH = "27LMNOP4321Q1Z4";
    private static final String VENDOR_GJ = "24LMNOP4321Q1ZA";

    /** May's 2B: the vendor reported INV/0012 as "inv-12", A-7 with different figures, X-99, and a credit note. */
    private static final String MAY_2B = """
            {"data": {"gstin": "27PQRSX5678K1ZY", "rtnprd": "052026", "docdata": {
              "b2b": [{"ctin": "27LMNOP4321Q1Z4", "trdnm": "Rao Wholesale", "inv": [
                {"inum": "inv-12", "dt": "05-05-2026", "val": 1180, "pos": "27", "rev": "N", "itcavl": "Y",
                 "items": [{"rt": 18, "txval": 1000, "igst": 0, "cgst": 90, "sgst": 90, "cess": 0}]},
                {"inum": "A-7", "dt": "06-05-2026", "val": 588, "pos": "27", "rev": "N", "itcavl": "Y",
                 "items": [{"rt": 18, "txval": 500, "igst": 0, "cgst": 44, "sgst": 44, "cess": 0}]},
                {"inum": "X-99", "dt": "09-05-2026", "val": 354, "pos": "27", "rev": "N", "itcavl": "Y",
                 "items": [{"rt": 18, "txval": 300, "igst": 0, "cgst": 27, "sgst": 27, "cess": 0}]}]}],
              "cdnr": [{"ctin": "27LMNOP4321Q1Z4", "nt": [
                {"ntnum": "CN-3", "typ": "C", "dt": "20-05-2026", "val": 236, "rev": "N", "itcavl": "Y",
                 "items": [{"rt": 18, "txval": 200, "cgst": 18, "sgst": 18}]}]}]}}}
            """;

    /** June's 2B: the Gujarat vendor reports May's G-1 late. */
    private static final String JUNE_2B = """
            {"data": {"gstin": "27PQRSX5678K1ZY", "rtnprd": "062026", "docdata": {
              "b2b": [{"ctin": "24LMNOP4321Q1ZA", "inv": [
                {"inum": "G-1", "dt": "07-05-2026", "val": 2360, "pos": "27", "rev": "N", "itcavl": "Y",
                 "items": [{"rt": 18, "txval": 2000, "igst": 360, "cgst": 0, "sgst": 0, "cess": 0}]}]}]}}}
            """;

    @TempDir
    Path dir;

    private CompanyService service;
    private PurchaseBilling purchases;
    private GstReturns returns;
    private Parties parties;
    private Party rao;
    private Party gujarat;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        ModuleTransactions transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.REGULAR, COMPANY_MH, "27", APRIL_1,
                RULES);
        purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        returns = new GstReturns(transactions, guard, () -> RULES, clock);
        parties = new Parties(transactions);
        rao = party("Rao Wholesale", VENDOR_MH);
        gujarat = party("Surat Traders", VENDOR_GJ);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private Party party(String name, String gstin) {
        return parties.create(name, PartyRole.VENDOR, new PartyProfile(null, null, null, null, null, null, null, null,
                gstin, null, null, null), 0);
    }

    private static LineRequest supplies(String amount) {
        return LineRequest.text("Supplies", d("1"), d(amount)).withTax(TaxCategory.TAXABLE, d("18"), null)
                .withHsn("9405");
    }

    private Document bill(Party vendor, int day, String amount, String supplierNo) {
        DraftRequest request = DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(), LocalDate.of(2026, 5, day),
                List.of(supplies(amount)));
        return purchases.post(purchases.createDraft(supplierNo == null ? request
                : request.withSupplierDocumentNo(supplierNo)).id());
    }

    /** May's books: five bills and a debit note. */
    private void mayBooks() {
        Document twelve = bill(rao, 5, "1000", "INV/0012");        // matches "inv-12" once normalised
        bill(rao, 6, "500", "A-7");                               // 590 in books, 588 in 2B
        bill(gujarat, 7, "2000", "G-1");                          // IGST 360; reported only in June
        bill(rao, 8, "100", null);                                // no supplier number recorded
        purchases.post(purchases.createDraft(DraftRequest.note(DocumentKind.DEBIT_NOTE, rao.id(),
                LocalDate.of(2026, 5, 20), twelve.id(), List.of(supplies("200")))
                .withSupplierDocumentNo("CN-3")).id());          // the supplier's credit note CN-3
    }

    private static Gstr2bReconciliation.Row row(Gstr2bReconciliation r, String number) {
        return r.rows().stream().filter(x -> x.number().equals(number)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("matched, mismatched, only in 2B and only in books - each with its reason")
    void reconciles() {
        mayBooks();
        Gstr2bImported imported = returns.importGstr2b(MAY_2B);
        assertThat(imported.documents()).isEqualTo(4);

        Gstr2bReconciliation r = returns.reconcileGstr2b(MAY);

        assertThat(row(r, "inv-12").status()).isEqualTo(Status.MATCHED);
        assertThat(row(r, "inv-12").exactNumber()).isFalse();
        assertThat(row(r, "A-7").status()).isEqualTo(Status.MISMATCHED);
        assertThat(row(r, "A-7").differences()).containsExactly("value: books 590.00, 2B 588.00");
        assertThat(row(r, "CN-3").status()).isEqualTo(Status.MATCHED);
        assertThat(row(r, "CN-3").type()).isEqualTo("CN");
        assertThat(row(r, "X-99").status()).isEqualTo(Status.ONLY_IN_2B);
        assertThat(row(r, "G-1").status()).isEqualTo(Status.ONLY_IN_BOOKS);
        assertThat(r.with(Status.ONLY_IN_BOOKS)).anySatisfy(x -> assertThat(x.differences())
                .containsExactly("No supplier document number recorded on the bill"));
    }

    @Test
    @DisplayName("P9-19: GSTR-3B claims the lower of books and 2B, reverses notes, and defers what 2B lacks")
    void creditOnlyAsIn2b() {
        mayBooks();
        returns.importGstr2b(MAY_2B);

        Gstr3b r = returns.gstr3b(MAY);

        // CGST: 90 (inv-12) + 44 (A-7: lower of 45 and 44) - 18 (CN-3) = 116; SGST the same.
        assertThat(r.itcOther().cgst()).isEqualByComparingTo("116");
        assertThat(r.itcOther().sgst()).isEqualByComparingTo("116");
        assertThat(r.itcOther().igst()).as("G-1 is not in May's 2B").isEqualByComparingTo("0");
        assertThat(r.itcDeferred().igst()).isEqualByComparingTo("360");
        assertThat(r.itcDeferred().cgst()).as("the bill without a supplier number").isEqualByComparingTo("9");
        assertThat(r.fileable()).isTrue();
    }

    @Test
    @DisplayName("a bill the supplier reports a month late is claimed in that month; May's matches are not claimed again")
    void lateReported() {
        mayBooks();
        returns.importGstr2b(MAY_2B);
        returns.importGstr2b(JUNE_2B);

        Gstr2bReconciliation june = returns.reconcileGstr2b(JUNE);
        Gstr3b r = returns.gstr3b(JUNE);

        assertThat(row(june, "G-1").status()).isEqualTo(Status.MATCHED);
        assertThat(june.rows()).noneMatch(x -> x.number().equals("inv-12") || x.number().equals("INV/0012"));
        assertThat(r.itcOther().igst()).isEqualByComparingTo("360");
        assertThat(r.itcOther().cgst()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("without the month's 2B, GSTR-3B shows credit as per books but cannot be filed")
    void blockedWithout2b() {
        mayBooks();

        Gstr3b r = returns.gstr3b(MAY);

        assertThat(r.fileable()).isFalse();
        assertThat(r.issues()).anySatisfy(i -> assertThat(i.message()).contains("Import GSTR-2B"));
        assertThatThrownBy(() -> returns.markGstr3bFiled(MAY, LocalDate.of(2026, 6, 20), null))
                .isInstanceOf(ReturnException.class).hasMessageContaining("Import GSTR-2B");
    }

    @Test
    @DisplayName("re-importing a month supersedes the earlier file; another GSTIN's file is refused")
    void imports() {
        assertThat(returns.importGstr2b(MAY_2B).supersededEarlier()).isFalse();
        assertThat(returns.importGstr2b(MAY_2B).supersededEarlier()).isTrue();

        assertThatThrownBy(() -> returns.importGstr2b(MAY_2B.replace("27PQRSX5678K1ZY", "29ABCDE1234F1ZW")))
                .hasMessageContaining("not the company's");
    }
}
