package com.accubook.trade;

import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyProfile;
import com.accubook.accounting.party.PartyRole;
import com.accubook.accounting.party.PartyStatement;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.app.company.CompanyService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleNotWritableException;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Receipts, payments, allocations and the reports built on them. */
@DisplayName("Settlement")
class SettlementEngineIT {

    private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);
    private static final ModuleId SALES = ModuleId.of("sales-invoicing");
    private static final ModuleId PURCHASE = ModuleId.of("purchase-billing");

    @TempDir
    Path dir;

    private final Map<String, ModuleState> states = new HashMap<>();
    private CompanyService service;
    private ModuleTransactions transactions;
    private DocumentEngine sales;
    private SettlementEngine receipts;
    private DocumentEngine purchases;
    private SettlementEngine payments;

    private Party customer;
    private Party vendor;
    private String cash;
    private String salesLedger;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()),
                id -> states.getOrDefault(id, ModuleState.ACTIVE));
        String companyId = service.createCompany("Asha Traders").id();
        service.openCompany(companyId);
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);
        TransactionalPosting posting = new TransactionalPosting(guard, clock);
        sales = new DocumentEngine(DocumentKind.Side.SALES, SALES, transactions, guard, posting, Optional.empty(), clock);
        receipts = new SettlementEngine(DocumentKind.Side.SALES, SALES, transactions, guard, posting, clock);
        purchases = new DocumentEngine(DocumentKind.Side.PURCHASE, PURCHASE, transactions, guard, posting,
                Optional.empty(), clock);
        payments = new SettlementEngine(DocumentKind.Side.PURCHASE, PURCHASE, transactions, guard, posting, clock);

        Parties parties = new Parties(transactions);
        customer = parties.create("Mehta Stores", PartyRole.CUSTOMER,
                new PartyProfile(null, null, null, null, null, null, null, null, null, null, null, 30), 0);
        vendor = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);
        cash = scalar("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'");
        salesLedger = scalar("SELECT id FROM ledger WHERE system_code = 'SALES'");
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    // -------------------------------------------------------------- helpers

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private String scalar(String sql) throws SQLException {
        return transactions.read(connection -> {
            try (var s = connection.createStatement(); var rows = s.executeQuery(sql)) {
                return rows.next() ? rows.getString(1) : null;
            }
        });
    }

    private Document invoice(LocalDate date, String amount) {
        return sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), date,
                List.of(LineRequest.text("Goods", d("1"), d(amount))))).id());
    }

    private Settlement receive(LocalDate date, String amount, AllocationRequest... allocations) {
        return receipts.record(SettlementRequest.of(customer.id(), date, d(amount), cash, PaymentMode.UPI,
                List.of(allocations)));
    }

    private static AllocationRequest to(Document document, String amount) {
        return new AllocationRequest(document.id(), d(amount));
    }

    private BigDecimal partyBalanceInTrialBalance(String partyName, LocalDate on) throws SQLException {
        return service.readOpenCompany(connection -> TrialBalanceQuery.compute(connection,
                        TrialBalanceQuery.defaultYear(TrialBalanceQuery.fiscalYears(connection), on).orElseThrow().id()))
                .rows().stream().filter(r -> r.ledgerName().equals(partyName)).findFirst()
                .map(r -> r.debit().subtract(r.credit())).orElseThrow();
    }

    // ---------------------------------------------------------------- tests

    @Nested
    @DisplayName("receipts and allocation (R-6, P7-3)")
    class Receipts {

        @Test
        @DisplayName("one receipt pays two invoices; outstanding and status are derived")
        void oneReceiptManyInvoices() {
            Document first = invoice(MAY_1, "1000");
            Document second = invoice(MAY_1, "500");

            Settlement receipt = receive(MAY_1.plusDays(5), "1200", to(first, "1000"), to(second, "200"));

            assertThat(receipt.number()).isEqualTo("RCT/26-27/00001");
            assertThat(receipt.unallocated()).isEqualByComparingTo("0");
            assertThat(receipts.outstanding(first.id(), MAY_1.plusDays(5))).isEqualByComparingTo("0");
            assertThat(receipts.outstanding(second.id(), MAY_1.plusDays(5))).isEqualByComparingTo("300");
            assertThat(receipts.status(first.id(), MAY_1.plusDays(5))).isEqualTo(SettlementStatus.SETTLED);
            assertThat(receipts.status(second.id(), MAY_1.plusDays(5))).isEqualTo(SettlementStatus.PARTIAL);
            assertThat(receipts.status(second.id(), MAY_1.plusDays(45))).isEqualTo(SettlementStatus.OVERDUE);
        }

        @Test
        @DisplayName("an advance waits on account and is applied later, counting from the invoice date")
        void advance() {
            Settlement advance = receive(MAY_1, "800");
            assertThat(advance.unallocated()).isEqualByComparingTo("800");
            assertThat(receipts.unallocatedCredits(MAY_1)).singleElement()
                    .satisfies(c -> assertThat(c.unallocated()).isEqualByComparingTo("800"));

            Document later = invoice(MAY_1.plusDays(10), "1000");
            receipts.allocate(Allocation.SourceType.SETTLEMENT, advance.id(), to(later, "800"));

            assertThat(receipts.outstanding(later.id(), MAY_1.plusDays(10))).isEqualByComparingTo("200");
            assertThat(receipts.allocationsOf(later.id())).singleElement()
                    .satisfies(a -> assertThat(a.effectiveDate()).isEqualTo(MAY_1.plusDays(10)));
            assertThat(receipts.unallocatedCredits(MAY_1.plusDays(10))).isEmpty();
        }

        @Test
        @DisplayName("over-allocation is refused, and refusing it refuses the whole receipt")
        void overAllocation() throws SQLException {
            Document inv = invoice(MAY_1, "1000");

            assertThatThrownBy(() -> receive(MAY_1, "500", to(inv, "600"))).hasMessageContaining("exceed");
            assertThatThrownBy(() -> receive(MAY_1, "2000", to(inv, "1000.01"))).hasMessageContaining("outstanding");
            assertThat(scalar("SELECT COUNT(*) FROM sales_settlement")).isEqualTo("0");
            assertThat(scalar("SELECT COUNT(*) FROM voucher WHERE voucher_type_id = "
                    + "(SELECT id FROM voucher_type WHERE code = 'RECEIPT')")).isEqualTo("0");

            Settlement partial = receive(MAY_1, "400", to(inv, "400"));
            assertThatThrownBy(() -> receipts.allocate(Allocation.SourceType.SETTLEMENT, partial.id(), to(inv, "1")))
                    .isInstanceOf(TradeException.class);
        }

        @Test
        @DisplayName("refuses a vendor-only party and an account outside Cash & Bank")
        void validation() {
            assertThatThrownBy(() -> receipts.record(SettlementRequest.of(vendor.id(), MAY_1, d("10"), cash,
                    PaymentMode.CASH, List.of()))).hasMessageContaining("vendor only");
            assertThatThrownBy(() -> receipts.record(SettlementRequest.of(customer.id(), MAY_1, d("10"), salesLedger,
                    PaymentMode.CASH, List.of()))).hasMessageContaining("Cash & Bank");
        }
    }

    @Nested
    @DisplayName("cancellation")
    class Cancellation {

        @Test
        @DisplayName("a bounced receipt reopens its invoices from the cancellation date; earlier as-of reports are unchanged")
        void bouncedCheque() throws SQLException {
            Document inv = invoice(MAY_1, "1000");
            Settlement cheque = receive(MAY_1.plusDays(2), "1000", to(inv, "1000"));

            receipts.cancel(cheque.id(), "Cheque bounced", MAY_1.plusDays(9));

            assertThat(receipts.outstanding(inv.id(), MAY_1.plusDays(9))).isEqualByComparingTo("1000");
            // R-12: on day 5 the invoice was paid, and history still says so.
            assertThat(receipts.outstanding(inv.id(), MAY_1.plusDays(5))).isEqualByComparingTo("0");
            assertThat(partyBalanceInTrialBalance("Mehta Stores", MAY_1)).isEqualByComparingTo("1000");
            assertThat(receipts.allocationsOf(inv.id())).singleElement()
                    .satisfies(a -> assertThat(a.removedDate()).isEqualTo(MAY_1.plusDays(9)));
        }

        @Test
        @DisplayName("P7-12: an invoice with payments applied cannot be cancelled until they are removed")
        void invoiceWithAllocations() {
            Document inv = invoice(MAY_1, "1000");
            Settlement receipt = receive(MAY_1, "300", to(inv, "300"));

            assertThatThrownBy(() -> sales.cancel(inv.id(), "Duplicate", MAY_1)).hasMessageContaining("payments applied");

            String allocation = receipts.allocationsOf(inv.id()).get(0).id();
            receipts.removeAllocation(allocation, "Applied to the wrong invoice", MAY_1);
            sales.cancel(inv.id(), "Duplicate", MAY_1);
            assertThat(receipts.find(receipt.id()).orElseThrow().unallocated()).isEqualByComparingTo("300");
        }
    }

    @Nested
    @DisplayName("notes (P7-13)")
    class Notes {

        @Test
        @DisplayName("a posted credit note is applied to its invoice automatically")
        void appliedAutomatically() {
            Document inv = invoice(MAY_1, "1000");
            sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), MAY_1, inv.id(),
                    List.of(LineRequest.text("Damaged", d("1"), d("400"))))).id());

            assertThat(receipts.outstanding(inv.id(), MAY_1)).isEqualByComparingTo("600");
            assertThat(receipts.unallocatedCredits(MAY_1)).isEmpty();
        }

        @Test
        @DisplayName("a note on an already-paid invoice becomes an unallocated credit")
        void excessIsCredit() {
            Document inv = invoice(MAY_1, "1000");
            receive(MAY_1, "1000", to(inv, "1000"));
            Document note = sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(),
                    MAY_1, inv.id(), List.of(LineRequest.text("Rate difference", d("1"), d("150"))))).id());

            assertThat(receipts.unallocatedCredits(MAY_1)).singleElement().satisfies(c -> {
                assertThat(c.sourceId()).isEqualTo(note.id());
                assertThat(c.unallocated()).isEqualByComparingTo("150");
            });
        }

        @Test
        @DisplayName("cancelling a note removes its allocation and reopens the invoice")
        void cancelNote() {
            Document inv = invoice(MAY_1, "1000");
            Document note = sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(),
                    MAY_1, inv.id(), List.of(LineRequest.text("Damaged", d("1"), d("400"))))).id());

            sales.cancel(note.id(), "Raised in error", MAY_1.plusDays(1));

            assertThat(receipts.outstanding(inv.id(), MAY_1.plusDays(1))).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("a note cannot be dated before the document it corrects")
        void noteDate() {
            Document inv = invoice(MAY_1.plusDays(5), "1000");
            assertThatThrownBy(() -> sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(),
                    MAY_1, inv.id(), List.of(LineRequest.text("x", d("1"), d("1"))))))
                    .hasMessageContaining("cannot be dated before");
        }
    }

    @Nested
    @DisplayName("aging (P7-15, R-12, R-13)")
    class AgingReports {

        @Test
        @DisplayName("buckets by days past due; notes and receipts are never aged as owed")
        void buckets() {
            invoice(LocalDate.of(2026, 4, 1), "100");        // due May 1
            invoice(LocalDate.of(2026, 5, 20), "200");       // due June 19
            Document third = invoice(LocalDate.of(2026, 4, 10), "300");   // due May 10
            sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(),
                    LocalDate.of(2026, 4, 15), third.id(), List.of(LineRequest.text("x", d("1"), d("50"))))).id());
            receive(LocalDate.of(2026, 5, 25), "75");        // advance

            Aging aging = receipts.aging(LocalDate.of(2026, 6, 15));

            Map<AgingBucket, BigDecimal> totals = aging.totals();
            assertThat(totals.get(AgingBucket.DAYS_31_60)).isEqualByComparingTo("350");   // 100 + (300 - 50)
            assertThat(totals.get(AgingBucket.NOT_DUE)).isEqualByComparingTo("200");
            assertThat(aging.documents()).allSatisfy(doc -> assertThat(doc.number()).startsWith("INV/"));
            assertThat(aging.totalCredits()).isEqualByComparingTo("75");
        }

        @Test
        @DisplayName("R-12: an as-of date excludes later invoices and later payments")
        void asOfIsReal() {
            Document april = invoice(LocalDate.of(2026, 4, 20), "500");
            receive(LocalDate.of(2026, 6, 1), "500", to(april, "500"));
            invoice(LocalDate.of(2026, 5, 15), "900");

            Aging endOfApril = receipts.aging(LocalDate.of(2026, 4, 30));
            assertThat(endOfApril.totalOutstanding()).isEqualByComparingTo("500");

            assertThat(receipts.aging(LocalDate.of(2026, 6, 1)).totalOutstanding()).isEqualByComparingTo("900");
        }
    }

    @Nested
    @DisplayName("P7 exit: invoice -> receipt -> ledger -> statement")
    class Reconciliation {

        @Test
        @DisplayName("the statement, the trial balance and outstanding-minus-advances agree to the paisa")
        void reconciles() throws SQLException {
            Document a = invoice(LocalDate.of(2026, 4, 5), "1180.40");      // rounds to 1180
            Document b = invoice(LocalDate.of(2026, 4, 25), "2499.50");     // rounds to 2500
            receive(LocalDate.of(2026, 5, 2), "1500", to(a, "1180"), to(b, "200"));
            sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(),
                    LocalDate.of(2026, 5, 10), b.id(), List.of(LineRequest.text("Short supply", d("1"), d("300"))))
                    .withRoundOff(false)).id());
            Settlement bounced = receive(LocalDate.of(2026, 5, 12), "1000", to(b, "1000"));
            receipts.cancel(bounced.id(), "Cheque bounced", LocalDate.of(2026, 5, 20));
            receive(LocalDate.of(2026, 5, 28), "650");

            LocalDate end = LocalDate.of(2026, 5, 31);
            PartyStatement statement = service.readOpenCompany(connection ->
                    PartyStatement.compute(connection, customer.id(), LocalDate.of(2026, 4, 1), end));
            Aging aging = receipts.aging(end);

            // 1180 + 2500 - 1500 - 300 + 1000 - 1000 - 650 = 1230
            assertThat(statement.closing()).isEqualByComparingTo("1230");
            assertThat(partyBalanceInTrialBalance("Mehta Stores", end)).isEqualByComparingTo(statement.closing());
            assertThat(aging.totalOutstanding().subtract(aging.totalCredits()))
                    .isEqualByComparingTo(statement.closing());
            assertThat(statement.lines()).hasSize(7);
            assertThat(statement.lines().get(statement.lines().size() - 1).balance())
                    .isEqualByComparingTo(statement.closing());
        }

        @Test
        @DisplayName("a statement starting mid-way opens with the earlier movement")
        void openingBalance() throws SQLException {
            invoice(LocalDate.of(2026, 4, 5), "1000");
            receive(LocalDate.of(2026, 5, 2), "400");

            PartyStatement may = service.readOpenCompany(connection ->
                    PartyStatement.compute(connection, customer.id(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)));

            assertThat(may.opening()).isEqualByComparingTo("1000");
            assertThat(may.lines()).singleElement().satisfies(l -> assertThat(l.credit()).isEqualByComparingTo("400"));
            assertThat(may.closing()).isEqualByComparingTo("600");
        }
    }

    @Nested
    @DisplayName("purchase side and entitlement")
    class PurchaseSide {

        @Test
        @DisplayName("a payment debits the vendor, credits cash, and settles the bill")
        void payment() throws SQLException {
            Document bill = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                    vendor.id(), MAY_1, List.of(LineRequest.text("Stationery", d("1"), d("2400"))))).id());

            Settlement payment = payments.record(SettlementRequest.of(vendor.id(), MAY_1.plusDays(3), d("2400"),
                    cash, PaymentMode.NEFT, List.of(new AllocationRequest(bill.id(), d("2400")))));

            assertThat(payment.number()).isEqualTo("PAY/26-27/00001");
            assertThat(partyBalanceInTrialBalance("Rao Wholesale", MAY_1)).isEqualByComparingTo("0");
            assertThat(payments.outstanding(bill.id(), MAY_1.plusDays(3))).isEqualByComparingTo("0");
            assertThat(payments.aging(MAY_1.plusDays(3)).totalOutstanding()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("a READ_ONLY sales module cannot record a receipt; its history stays readable")
        void readOnly() {
            Document inv = invoice(MAY_1, "100");
            receive(MAY_1, "100", to(inv, "100"));
            states.put("sales-invoicing", ModuleState.READ_ONLY);

            assertThatThrownBy(() -> receive(MAY_1, "1")).isInstanceOf(ModuleNotWritableException.class);
            assertThat(receipts.list()).hasSize(1);
            assertThat(receipts.outstanding(inv.id(), MAY_1)).isEqualByComparingTo("0");
        }
    }
}
