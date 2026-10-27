package com.accubook.integration;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.accounting.report.Registers;
import com.accubook.accounting.year.FiscalYears;
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
import com.accubook.trade.AgingBucket;
import com.accubook.trade.Allocation;
import com.accubook.trade.AllocationRequest;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.LineRequest;
import com.accubook.trade.OpeningBill;
import com.accubook.trade.OpeningBillRequest;
import com.accubook.trade.OutstandingDocument;
import com.accubook.trade.PaymentMode;
import com.accubook.trade.Settlement;
import com.accubook.trade.SettlementRequest;
import com.accubook.trade.TradeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S2-4: a party's opening position bill by bill - posted, aged, paid, cancelled, and never a supply. */
@DisplayName("Opening bills")
class OpeningBillsIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private SalesInvoicing sales;
    private PurchaseBilling purchases;
    private FiscalYears years;
    private Party mehta;
    private Party rao;
    private String cash;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders", 4, APRIL_1).id());
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.UNREGISTERED, null, "27",
                LocalDate.of(2017, 7, 1), RULES);
        sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        years = new FiscalYears(transactions, guard, clock);
        Parties parties = new Parties(transactions);
        mehta = parties.create("Mehta Stores", PartyRole.CUSTOMER, null, 0);
        rao = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);
        cash = scalar("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'");
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private String scalar(String sql) throws Exception {
        return service.readOpenCompany(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        });
    }

    private BigDecimal balance(String ledgerName) throws Exception {
        return new BigDecimal(scalar("SELECT COALESCE(SUM(e.debit_amount_e4 - e.credit_amount_e4), 0) FROM "
                + "voucher_entry e JOIN voucher v ON v.id = e.voucher_id JOIN ledger l ON l.id = e.ledger_id "
                + "WHERE v.is_posted = 1 AND l.name = '" + ledgerName + "'")).movePointLeft(4);
    }

    private OpeningBill customerBill(String number, String amount) {
        return sales.openingBills().create(new OpeningBillRequest(mehta.id(), number, LocalDate.of(2026, 2, 10),
                LocalDate.of(2026, 3, 12), d(amount), null));
    }

    private Settlement receipt(String amount, AllocationRequest... allocations) {
        return sales.settlements().record(SettlementRequest.of(mehta.id(), MAY_10, d(amount), cash, PaymentMode.CASH,
                List.of(allocations)));
    }

    @Test
    @DisplayName("a customer's opening invoice posts on the first day against Opening Balance Equity, and ages from its due date")
    void postsAndAges() throws Exception {
        OpeningBill bill = customerBill("INV-0991", "11800");

        assertThat(balance("Mehta Stores")).isEqualByComparingTo("11800");
        assertThat(balance("Opening Balance Equity")).isEqualByComparingTo("-11800");
        assertThat(scalar("SELECT voucher_date FROM voucher WHERE id = '" + bill.voucherId() + "'"))
                .isEqualTo("2026-04-01");
        OutstandingDocument owed = sales.settlements().outstanding(MAY_10).stream()
                .filter(o -> o.documentId().equals(bill.id())).findFirst().orElseThrow();
        assertThat(owed.openingBill()).isTrue();
        assertThat(owed.date()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(owed.daysPastDue()).as("12 March to 10 May").isEqualTo(59);
        assertThat(owed.bucket()).isEqualTo(AgingBucket.DAYS_31_60);
    }

    @Test
    @DisplayName("never a supply: the sales register and GST queries read documents, which it is not")
    void notASupply() throws Exception {
        customerBill("INV-0991", "11800");
        FiscalYear year = years.years().get(0);

        assertThat(new Registers(transactions).salesRegister(LocalDate.of(2025, 4, 1), year.endDate()).rows())
                .isEmpty();
        assertThat(scalar("SELECT COUNT(*) FROM sales_document")).isEqualTo("0");
    }

    @Test
    @DisplayName("receipts pay it like an invoice; a receipt's limit counts what it paid to invoices and opening bills")
    void paidLikeAnInvoice() throws Exception {
        OpeningBill bill = customerBill("INV-0991", "11800");
        receipt("5000", new AllocationRequest(bill.id(), d("5000")));
        assertThat(sales.openingBills().find(bill.id()).orElseThrow().outstanding()).isEqualByComparingTo("6800");

        assertThatThrownBy(() -> receipt("7000", new AllocationRequest(bill.id(), d("7000"))))
                .isInstanceOf(TradeException.class).hasMessageContaining("Only 6800.00 is outstanding");

        Document invoice = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, mehta.id(),
                MAY_10, List.of(LineRequest.text("Goods", d("1"), d("1000"))))).id());
        Settlement split = receipt("1000", new AllocationRequest(invoice.id(), d("600")));
        assertThatThrownBy(() -> sales.settlements().allocate(Allocation.SourceType.SETTLEMENT, split.id(),
                new AllocationRequest(bill.id(), d("600")))).hasMessageContaining("holds");
        sales.settlements().allocate(Allocation.SourceType.SETTLEMENT, split.id(), new AllocationRequest(bill.id(),
                d("400")));

        assertThat(sales.settlements().find(split.id()).orElseThrow().unallocated()).isEqualByComparingTo("0");
        assertThat(sales.openingBills().find(bill.id()).orElseThrow().outstanding()).isEqualByComparingTo("6400");
        assertThat(sales.settlements().allocationsOf(bill.id())).hasSize(2);
        assertThat(sales.settlements().unallocatedCredits(MAY_10)).isEmpty();
    }

    @Test
    @DisplayName("cancelled only with nothing applied; its voucher is reversed and it no longer ages")
    void cancel() throws Exception {
        OpeningBill bill = customerBill("INV-0991", "11800");
        Settlement paid = receipt("100", new AllocationRequest(bill.id(), d("100")));
        assertThatThrownBy(() -> sales.openingBills().cancel(bill.id(), "Wrong party"))
                .hasMessageContaining("has payments applied");

        String allocation = sales.settlements().allocationsOf(bill.id()).get(0).id();
        sales.settlements().removeAllocation(allocation, "Was for another bill", MAY_10);
        assertThat(sales.settlements().find(paid.id()).orElseThrow().unallocated()).isEqualByComparingTo("100");
        sales.openingBills().cancel(bill.id(), "Wrong party");

        assertThat(balance("Mehta Stores")).as("the receipt of 100 stays").isEqualByComparingTo("-100");
        assertThat(sales.settlements().outstanding(MAY_10)).isEmpty();
        assertThat(sales.openingBills().find(bill.id()).orElseThrow().cancelled()).isTrue();
    }

    @Test
    @DisplayName("a note's unused credit can go to an opening bill, and comes off it when the note is cancelled")
    void noteCredit() throws Exception {
        OpeningBill bill = customerBill("INV-0991", "11800");
        Document invoice = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, mehta.id(),
                MAY_10, List.of(LineRequest.text("Goods", d("1"), d("1000"))))).id());
        receipt("1000", new AllocationRequest(invoice.id(), d("1000")));
        Document note = sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, mehta.id(), MAY_10,
                invoice.id(), List.of(LineRequest.text("Returned", d("1"), d("300"))))).id());
        sales.settlements().allocate(Allocation.SourceType.NOTE, note.id(), new AllocationRequest(bill.id(), d("300")));
        assertThat(sales.openingBills().find(bill.id()).orElseThrow().outstanding()).isEqualByComparingTo("11500");

        sales.cancel(note.id(), "Goods not returned after all", MAY_10);

        assertThat(sales.openingBills().find(bill.id()).orElseThrow().outstanding()).isEqualByComparingTo("11800");
    }

    @Test
    @DisplayName("a vendor's opening bill is on the purchase side, paid by payments")
    void vendorSide() throws Exception {
        OpeningBill bill = purchases.openingBills().create(new OpeningBillRequest(rao.id(), "RW/77",
                LocalDate.of(2026, 3, 1), null, d("5900"), "Rice"));
        assertThat(balance("Rao Wholesale")).isEqualByComparingTo("-5900");
        assertThat(balance("Opening Balance Equity")).isEqualByComparingTo("5900");

        purchases.settlements().record(SettlementRequest.of(rao.id(), MAY_10, d("5900"), cash, PaymentMode.CASH,
                List.of(new AllocationRequest(bill.id(), d("5900")))));

        assertThat(balance("Rao Wholesale")).isEqualByComparingTo("0");
        assertThat(purchases.settlements().outstanding(MAY_10)).isEmpty();
    }

    @Test
    @DisplayName("refused: dated within the books, the wrong side, a repeated number, after year 1 closes")
    void guards() throws Exception {
        assertThatThrownBy(() -> sales.openingBills().create(new OpeningBillRequest(mehta.id(), "X", APRIL_1, null,
                d("1"), null))).hasMessageContaining("before the books begin");
        assertThatThrownBy(() -> sales.openingBills().create(new OpeningBillRequest(rao.id(), "X",
                LocalDate.of(2026, 3, 1), null, d("1"), null))).hasMessageContaining("purchase side");
        customerBill("INV-0991", "100");
        assertThatThrownBy(() -> customerBill("INV-0991", "200")).hasMessageContaining("already has an opening bill");
        assertThatThrownBy(() -> new OpeningBillRequest(mehta.id(), "Y", LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 2, 1), d("1"), null)).hasMessageContaining("fall due before");

        years.close(years.years().get(0).id(), "owner");
        assertThatThrownBy(() -> customerBill("INV-0992", "100")).hasMessageContaining("first year is closed");
    }

    @Test
    @DisplayName("the schema holds the rules too: no bill within the books, none edited")
    void schema() throws Exception {
        OpeningBill bill = customerBill("INV-0991", "100");
        assertThatThrownBy(() -> transactions.inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                return s.executeUpdate("UPDATE sales_opening_bill SET amount_e4 = 1000000 WHERE id = '"
                        + bill.id() + "'");
            }
        })).hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> transactions.inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                return s.executeUpdate("INSERT INTO sales_opening_bill (id, party_id, number, bill_date, amount_e4, "
                        + "fiscal_year_id, voucher_id, created_at) SELECT 'x', party_id, 'Z', '2026-04-01', 100, "
                        + "fiscal_year_id, voucher_id, 0 FROM sales_opening_bill");
            }
        })).hasMessageContaining("before the books begin");
    }
}
