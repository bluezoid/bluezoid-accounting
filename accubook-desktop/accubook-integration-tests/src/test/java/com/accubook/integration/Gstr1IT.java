package com.accubook.integration;

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
import com.accubook.inventory.Warehouse;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleNotWritableException;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.purchase.PurchaseBilling;
import com.accubook.returns.FiledReturn;
import com.accubook.returns.GstReturns;
import com.accubook.returns.Gstr1;
import com.accubook.returns.ReturnException;
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
import com.accubook.trade.TradeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P9d-1: GSTR-1 from posted documents, its portal JSON, and filing that locks the period. */
@DisplayName("GSTR-1")
class Gstr1IT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);
    private static final ReturnPeriod MAY = ReturnPeriod.month(2026, 5);
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";
    private static final String CUSTOMER_KA = "29ABCDE1234F1ZW";
    private static final String VENDOR_MH = "27LMNOP4321Q1Z4";

    @TempDir
    Path dir;

    private final Map<String, ModuleState> states = new HashMap<>();
    private CompanyService service;
    private ModuleTransactions transactions;
    private SalesInvoicing sales;
    private GstReturns returns;
    private Parties parties;
    private Inventory inventory;
    private Item lamp;
    private Warehouse main;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())),
                id -> states.getOrDefault(id, ModuleState.ACTIVE));
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.REGULAR, COMPANY_MH, "27", APRIL_1,
                RULES);
        sales = new SalesInvoicing(transactions, guard, Optional.of(new InventoryStockPort()), () -> RULES, clock);
        PurchaseBilling purchases = new PurchaseBilling(transactions, guard, Optional.of(new InventoryStockPort()),
                () -> RULES, clock);
        returns = new GstReturns(transactions, guard, () -> RULES, clock);
        parties = new Parties(transactions);
        inventory = new Inventory(transactions, guard, clock);
        Unit nos = inventory.createUnit("NOS", "Numbers", 0);
        inventory.setUnitUqc(nos.id(), "NOS", RULES);
        main = inventory.createWarehouse("Main", null);
        lamp = inventory.createItem("Lamp", ItemKind.GOODS, nos.id(), null, null, "9405", null);
        inventory.setTax(lamp.id(), APRIL_1, TaxCategory.TAXABLE, d("18"), null, RULES);
        purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                party("Rao Wholesale", PartyRole.VENDOR, VENDOR_MH, null).id(), APRIL_1,
                List.of(LineRequest.goods(lamp.id(), main.id(), d("100"), d("60"))))).id());
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

    private Document invoice(Party customer, LocalDate date, LineRequest... lines) {
        return sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), date,
                List.of(lines))).id());
    }

    private LineRequest lamps(String qty) {
        return LineRequest.goods(lamp.id(), main.id(), d(qty), d("100"));
    }

    /** A month of sales covering every GSTR-1 table P9d-1 builds. */
    private Document monthOfSales() {
        Party registered = party("Bengaluru Lights", PartyRole.CUSTOMER, CUSTOMER_KA, null);
        Party farAway = party("Mysore Buyer", PartyRole.CUSTOMER, null, "29");
        Party walkIn = party("Cash Customer", PartyRole.CUSTOMER, null, null);

        Document b2b = invoice(registered, MAY_10, lamps("10"));                            // INV 1: 1000 + IGST 180
        invoice(farAway, MAY_10, LineRequest.text("Chandelier", d("1"), d("110000"))
                .withTax(TaxCategory.TAXABLE, d("18"), null).withHsn("9405"));               // INV 2: B2CL, 129,800
        invoice(walkIn, MAY_10, lamps("2"));                                                 // INV 3: B2CS intra
        invoice(farAway, MAY_10, lamps("1"));                                                // INV 4: B2CS inter, small
        invoice(walkIn, MAY_10, LineRequest.text("Training", d("1"), d("500"))
                .withTax(TaxCategory.EXEMPT, null, null).withHsn("999293"));                 // INV 5: exempt
        Document cancelled = invoice(walkIn, MAY_10, lamps("5"));                            // INV 6: cancelled
        sales.cancel(cancelled.id(), "Wrong customer", MAY_10);
        sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, registered.id(), MAY_10, b2b.id(),
                List.of(lamps("2")))).id());                                                  // CN 1: 200 + IGST 36
        return b2b;
    }

    @Test
    @DisplayName("every table, from the stored figures: B2B, B2CL above 1 lakh, B2CS, CDNR, NIL, HSN and documents")
    void tables() {
        monthOfSales();

        Gstr1 r = returns.gstr1(MAY);

        assertThat(r.gstin()).isEqualTo(COMPANY_MH);
        assertThat(r.b2b()).singleElement().satisfies(b -> {
            assertThat(b.ctin()).isEqualTo(CUSTOMER_KA);
            assertThat(b.placeOfSupply()).isEqualTo("29");
            assertThat(b.value()).isEqualByComparingTo("1180");
            assertThat(b.items()).singleElement().satisfies(i -> {
                assertThat(i.taxable()).isEqualByComparingTo("1000");
                assertThat(i.igst()).isEqualByComparingTo("180");
            });
        });
        assertThat(r.b2cl()).singleElement().satisfies(b -> assertThat(b.value()).isEqualByComparingTo("129800"));
        assertThat(r.b2cs()).hasSize(2);
        assertThat(r.b2cs()).filteredOn(Gstr1.B2cs::interState).singleElement().satisfies(b -> {
            assertThat(b.placeOfSupply()).isEqualTo("29");
            assertThat(b.taxable()).isEqualByComparingTo("100");
            assertThat(b.igst()).isEqualByComparingTo("18");
        });
        assertThat(r.b2cs()).filteredOn(b -> !b.interState()).singleElement().satisfies(b -> {
            assertThat(b.taxable()).as("the cancelled invoice is left out").isEqualByComparingTo("200");
            assertThat(b.cgst()).isEqualByComparingTo("18");
            assertThat(b.sgst()).isEqualByComparingTo("18");
        });
        assertThat(r.cdnr()).singleElement().satisfies(n -> {
            assertThat(n.value()).isEqualByComparingTo("236");
            assertThat(n.items().get(0).igst()).isEqualByComparingTo("36");
        });
        assertThat(r.nil()).singleElement().satisfies(n -> {
            assertThat(n.interState()).isFalse();
            assertThat(n.registered()).isFalse();
            assertThat(n.exempt()).isEqualByComparingTo("500");
            assertThat(n.nilRated()).isEqualByComparingTo("0");
        });
        assertThat(r.hsn()).filteredOn(Gstr1.Hsn::registered).singleElement().satisfies(h -> {
            assertThat(h.uqc()).isEqualTo("NOS");
            assertThat(h.quantity()).as("10 sold less 2 returned").isEqualByComparingTo("8");
            assertThat(h.taxable()).isEqualByComparingTo("800");
        });
        assertThat(r.hsn()).filteredOn(h -> !h.registered()).extracting(h -> h.hsn() + "/" + h.uqc())
                .containsExactlyInAnyOrder("9405/NOS", "9405/OTH", "999293/NA");
        assertThat(r.docs()).extracting(Gstr1.DocRange::nature, Gstr1.DocRange::total, Gstr1.DocRange::cancelled)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Invoices for outward supply", 6, 1),
                        org.assertj.core.groups.Tuple.tuple("Credit Note", 1, 0));
        assertThat(r.docs().get(0).from()).isEqualTo("INV/26-27/00001");
        assertThat(r.docs().get(0).to()).isEqualTo("INV/26-27/00006");
        assertThat(r.issues()).as("the free-text chandelier has no unit: a warning only")
                .allSatisfy(i -> assertThat(i.blocking()).isFalse()).isNotEmpty();
        assertThat(r.fileable()).isTrue();
    }

    @Test
    @DisplayName("portal JSON: gstin, fp and the portal's field names")
    void json() {
        monthOfSales();

        String json = returns.gstr1Json(MAY);

        assertThat(json).startsWith("{\"gstin\":\"" + COMPANY_MH + "\",\"fp\":\"052026\"")
                .contains("\"ctin\":\"" + CUSTOMER_KA + "\"", "\"inum\":\"INV/26-27/00001\"",
                        "\"idt\":\"10-05-2026\"", "\"val\":1180.00", "\"num\":1801",
                        "\"txval\":1000.00", "\"iamt\":180.00", "\"sply_ty\":\"INTRA\"",
                        "\"ntty\":\"C\"", "\"totnum\":6", "\"cancel\":1", "\"net_issue\":5",
                        "\"uqc\":\"NOS\"", "\"expt_amt\":500.00");
    }

    @Test
    @DisplayName("P9-14: marking it filed keeps the JSON and its SHA-256 and locks May's sales; unlocking reopens them")
    void filingLocksThePeriod() throws Exception {
        Document b2b = monthOfSales();
        String json = returns.gstr1Json(MAY);

        FiledReturn filed = returns.markGstr1Filed(MAY, LocalDate.of(2026, 6, 11), "AA270626000001X");

        assertThat(returns.snapshot(filed.id())).isEqualTo(json);
        assertThat(filed.snapshotSha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))));
        Party walkIn = party("Late Customer", PartyRole.CUSTOMER, null, null);
        assertThatThrownBy(() -> invoice(walkIn, LocalDate.of(2026, 5, 31), lamps("1")))
                .isInstanceOf(TradeException.class).hasMessageContaining("GSTR-1 for 2026-05-01 to 2026-05-31 is filed");
        assertThatThrownBy(() -> sales.cancel(b2b.id(), "Late correction", LocalDate.of(2026, 6, 12)))
                .hasMessageContaining("credit note");
        assertThatThrownBy(() -> returns.markGstr1Filed(MAY, LocalDate.of(2026, 6, 12), null))
                .isInstanceOf(ReturnException.class).hasMessageContaining("already filed");
        // A correction dated in an open period is allowed.
        sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, b2b.partyId(),
                LocalDate.of(2026, 6, 12), b2b.id(), List.of(lamps("1")))).id());

        returns.unlock(filed.id(), "Amendment agreed with the CA", LocalDate.of(2026, 6, 20));

        assertThat(invoice(walkIn, LocalDate.of(2026, 5, 31), lamps("1")).number()).isNotNull();
        assertThat(returns.filed()).singleElement().satisfies(f -> {
            assertThat(f.unlocked()).isTrue();
            assertThat(f.unlockReason()).isEqualTo("Amendment agreed with the CA");
        });
    }

    @Test
    @DisplayName("P9-16: a taxable line without HSN/SAC blocks filing, though the return can be previewed")
    void missingHsnBlocks() {
        invoice(party("Cash Customer", PartyRole.CUSTOMER, null, null), MAY_10,
                LineRequest.text("Repairs", d("1"), d("1000")).withTax(TaxCategory.TAXABLE, d("18"), null));

        Gstr1 r = returns.gstr1(MAY);

        assertThat(r.fileable()).isFalse();
        assertThat(r.issues()).anySatisfy(i -> {
            assertThat(i.blocking()).isTrue();
            assertThat(i.message()).contains("\"Repairs\" has no HSN/SAC code");
        });
        assertThatThrownBy(() -> returns.markGstr1Filed(MAY, LocalDate.of(2026, 6, 11), null))
                .hasMessageContaining("issues to resolve");
    }

    @Test
    @DisplayName("GSTR-1 is a regular taxpayer's return; a quarter is one period")
    void regularOnly() {
        new GstRegistrations(transactions, Clock.systemUTC()).register(GstRegistration.Scheme.COMPOSITION, COMPANY_MH,
                "27", LocalDate.of(2026, 7, 1), RULES);

        assertThatThrownBy(() -> returns.gstr1(ReturnPeriod.quarter(2026, 7)))
                .hasMessageContaining("filed by a regular taxpayer");
        assertThat(returns.gstr1(ReturnPeriod.quarter(2026, 4)).b2b()).isEmpty();
    }

    @Test
    @DisplayName("an add-on gone read-only still previews, but files nothing")
    void readOnlyAddOn() {
        monthOfSales();
        states.put("taxation-returns", ModuleState.READ_ONLY);
        GstReturns readOnly = new GstReturns(transactions, new EntitlementGuard(service.moduleStates()), () -> RULES,
                Clock.systemUTC());

        assertThat(readOnly.gstr1(MAY).b2b()).hasSize(1);
        assertThatThrownBy(() -> readOnly.markGstr1Filed(MAY, LocalDate.of(2026, 6, 11), null))
                .isInstanceOf(ModuleNotWritableException.class);
    }
}
