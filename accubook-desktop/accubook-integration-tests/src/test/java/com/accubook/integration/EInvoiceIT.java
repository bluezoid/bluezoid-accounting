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
import com.accubook.returns.EInvoices;
import com.accubook.returns.EwayAdvice;
import com.accubook.returns.ReturnException;
import com.accubook.returns.Transport;
import com.accubook.sales.SalesInvoicing;
import com.accubook.tax.CompanyIdentity;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P9e: e-invoice and e-way bill payloads, and the portals' responses. No IRN or
 * e-way bill number is ever made up: the responses here are built by the test
 * in the portals' shape, as a user would import them.
 */
@DisplayName("E-invoice and e-way bill")
class EInvoiceIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);
    private static final String COMPANY_MH = "27PQRSX5678K1ZY";
    private static final String CUSTOMER_KA = "29ABCDE1234F1ZW";
    private static final String IRN = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-05-10T10:00:00Z");

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private EntitlementGuard guard;
    private SalesInvoicing sales;
    private EInvoices einvoices;
    private Parties parties;
    private Party buyer;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.REGULAR, COMPANY_MH, "27", APRIL_1,
                RULES);
        new CompanyIdentity(transactions).set(new CompanyIdentity.Address("Asha Traders Pvt Ltd", "12 MG Road",
                "Pune", "27", "411001"), RULES);
        sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        einvoices = new EInvoices(transactions, guard, () -> RULES, clock);
        parties = new Parties(transactions);
        buyer = party("Bengaluru Lights", CUSTOMER_KA, "4 Brigade Road", "Bengaluru", "560001");
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private Party party(String name, String gstin, String address, String city, String pin) {
        return parties.create(name, PartyRole.CUSTOMER, new PartyProfile(null, null, null, null, address, city, null,
                pin, gstin, null, null, null), 0);
    }

    private static LineRequest lamps(String qty, String rate) {
        return LineRequest.text("Lamps", d(qty), d(rate)).withTax(TaxCategory.TAXABLE, d("18"), null).withHsn("9405");
    }

    private Document invoice(Party to, LineRequest... lines) {
        return sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, to.id(), MAY_10,
                List.of(lines))).id());
    }

    /** A response in the IRP's shape: the signed invoice is a token whose payload carries the invoice. */
    private static String irpResponse(String number, String date, String gstin) {
        return irpResponse(number, date, gstin, IRN);
    }

    private static String irpResponse(String number, String date, String gstin, String irn) {
        String invoice = "{\"Irn\":\"" + irn + "\",\"DocDtls\":{\"Typ\":\"INV\",\"No\":\"" + number + "\",\"Dt\":\""
                + date + "\"},\"SellerDtls\":{\"Gstin\":\"" + gstin + "\"}}";
        String payload = "{\"data\":" + quote(invoice) + ",\"iss\":\"NIC\"}";
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String token = b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".c2lnbmF0dXJl";
        return "{\"AckNo\":112610000000001,\"AckDt\":\"2026-05-10 11:20:00\",\"Irn\":\"" + irn
                + "\",\"SignedInvoice\":\"" + token + "\",\"SignedQRCode\":\"qr-token\",\"Status\":\"ACT\"}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    @DisplayName("the IRP payload for a B2B invoice, from the stored figures")
    void payload() {
        einvoices.declare(APRIL_1, true);
        Document inv = invoice(buyer, lamps("10", "100"));

        String json = einvoices.einvoicePayload(inv.id());

        assertThat(json).contains("\"Version\":\"1.1\"", "\"SupTyp\":\"B2B\"", "\"Typ\":\"INV\"",
                "\"No\":\"" + inv.number() + "\"", "\"Dt\":\"10/05/2026\"", "\"Gstin\":\"" + COMPANY_MH + "\"",
                "\"LglNm\":\"Asha Traders Pvt Ltd\"", "\"Pin\":411001", "\"Pos\":\"29\"", "\"HsnCd\":\"9405\"",
                "\"Qty\":10.000", "\"Unit\":\"OTH\"", "\"UnitPrice\":100.000", "\"AssAmt\":1000.00",
                "\"IgstAmt\":180.00", "\"TotInvVal\":1180.00");
    }

    @Test
    @DisplayName("every problem is listed together: not declared, a B2C buyer without an address, no HSN")
    void problems() {
        Party walkIn = party("Cash Customer", null, null, null, null);
        Document inv = invoice(walkIn, LineRequest.text("Repairs", d("1"), d("100"))
                .withTax(TaxCategory.TAXABLE, d("18"), null));

        assertThatThrownBy(() -> einvoices.einvoicePayload(inv.id())).isInstanceOf(ReturnException.class)
                .hasMessageContaining("not declared to apply").hasMessageContaining("registered buyer")
                .hasMessageContaining("needs an address").hasMessageContaining("\"Repairs\" has no HSN/SAC code");
    }

    @Test
    @DisplayName("P9-28: the IRP's response is kept only if it names this invoice")
    void importResponse() {
        einvoices.declare(APRIL_1, true);
        Document inv = invoice(buyer, lamps("10", "100"));

        assertThatThrownBy(() -> einvoices.importIrpResponse(inv.id(), irpResponse("INV/26-27/00099", "10/05/2026",
                COMPANY_MH))).hasMessageContaining("not for " + inv.number());
        assertThatThrownBy(() -> einvoices.importIrpResponse(inv.id(), "{\"Irn\":\"" + IRN + "\"}"))
                .hasMessageContaining("SignedInvoice");

        einvoices.importIrpResponse(inv.id(), irpResponse(inv.number(), "10/05/2026", COMPANY_MH));

        assertThat(einvoices.irn(inv.id())).contains(IRN);
    }

    @Test
    @DisplayName("P9-26: an invoice with an IRN cancels within 24 hours of acknowledgement, not after")
    void cancelWindow() {
        einvoices.declare(APRIL_1, true);
        Document inv = invoice(buyer, lamps("10", "100"));
        Document late = invoice(buyer, lamps("5", "100"));
        einvoices.importIrpResponse(inv.id(), irpResponse(inv.number(), "10/05/2026", COMPANY_MH));
        String lateIrn = "b".repeat(64);
        einvoices.importIrpResponse(late.id(), irpResponse(late.number(), "10/05/2026", COMPANY_MH, lateIrn));

        // Acknowledged 11:20 IST = 05:50 UTC; it is 10:00 UTC: within the window.
        sales.cancel(inv.id(), "Wrong buyer", MAY_10);
        assertThat(einvoices.irnsToCancel()).containsExactly(IRN);

        SalesInvoicing twoDaysOn = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES,
                Clock.fixed(NOW.plusSeconds(2 * 24 * 3600), ZoneOffset.UTC));
        assertThatThrownBy(() -> twoDaysOn.cancel(late.id(), "Wrong buyer", LocalDate.of(2026, 5, 12)))
                .hasMessageContaining("more than 24 hours ago").hasMessageContaining("credit note");
    }

    @Test
    @DisplayName("R-25: an e-way bill is advised on value with tax - 45,000 + 18% is over 50,000; exempt goods are left out")
    void ewayAdvice() {
        Document taxed = invoice(buyer, lamps("1", "45000"));                         // 53,100 with IGST
        Document mixed = invoice(buyer, lamps("1", "40000"), LineRequest.text("Books", d("1"), d("10000"))
                .withTax(TaxCategory.EXEMPT, null, null).withHsn("4901"));            // 47,200 + exempt 10,000

        EwayAdvice a = einvoices.ewayAdvice(taxed.id());
        assertThat(a.required()).isTrue();
        assertThat(a.consignmentValue()).isEqualByComparingTo("53100");
        assertThat(a.limit()).isEqualByComparingTo("50000");
        assertThat(einvoices.ewayAdvice(mixed.id()).required()).isFalse();
        assertThat(einvoices.ewayAdvice(mixed.id()).consignmentValue()).isEqualByComparingTo("47200");
    }

    @Test
    @DisplayName("the e-way bill payload, and its details imported only when they name the invoice")
    void ewayBill() {
        Document inv = invoice(buyer, lamps("10", "6000"));

        String json = einvoices.ewayPayload(inv.id(), Transport.road(842, "mh12 ab-1234"));

        assertThat(json).contains("\"supplyType\":\"O\"", "\"docNo\":\"" + inv.number() + "\"",
                "\"fromPincode\":411001", "\"toGstin\":\"" + CUSTOMER_KA + "\"", "\"toPincode\":560001",
                "\"hsnCode\":9405", "\"igstRate\":18", "\"transDistance\":\"842\"", "\"vehicleNo\":\"MH12AB1234\"",
                "\"totInvValue\":70800.00");
        assertThatThrownBy(() -> einvoices.importEwayResponse(inv.id(),
                "{\"ewayBillNo\":331000000001,\"ewayBillDate\":\"10/05/2026 11:30:00 AM\"}"))
                .hasMessageContaining("details, which name the invoice");
        einvoices.importEwayResponse(inv.id(), "{\"ewbNo\":331000000001,\"ewayBillDate\":\"10/05/2026 11:30:00 AM\","
                + "\"validUpto\":\"14/05/2026 11:59:00 PM\",\"docNo\":\"" + inv.number() + "\",\"docDate\":"
                + "\"10/05/2026\",\"fromGstin\":\"" + COMPANY_MH + "\"}");
    }

    @Test
    @DisplayName("P9-25: a warning when the books pass 5 crore and e-invoicing is not declared")
    void turnoverWarning() {
        invoice(buyer, LineRequest.text("Machinery", d("1"), d("50000001")).withTax(TaxCategory.TAXABLE, d("18"), null)
                .withHsn("8479"));

        assertThat(einvoices.turnoverWarning(MAY_10)).hasValueSatisfying(w -> assertThat(w)
                .contains("above the e-invoicing limit of 50000000"));
        einvoices.declare(APRIL_1, true);
        assertThat(einvoices.turnoverWarning(MAY_10)).isEmpty();
    }
}
