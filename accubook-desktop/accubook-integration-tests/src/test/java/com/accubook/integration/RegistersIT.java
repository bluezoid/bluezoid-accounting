package com.accubook.integration;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.accounting.report.Registers;
import com.accubook.accounting.report.Table;
import com.accubook.accounting.report.TableExport;
import com.accubook.accounting.year.FiscalYears;
import com.accubook.accounting.year.OpeningBalance;
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
import com.accubook.trade.AllocationRequest;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.LineRequest;
import com.accubook.trade.PaymentMode;
import com.accubook.trade.SettlementRequest;

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

/** Phase 11c: the day book, a ledger account and the registers over a month of trade. Figures by hand. */
@DisplayName("Registers")
class RegistersIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate APRIL_1 = LocalDate.of(2026, 4, 1);
    private static final LocalDate MAY_1 = LocalDate.of(2026, 5, 1);
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);
    private static final LocalDate MAY_31 = LocalDate.of(2026, 5, 31);

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private Registers registers;
    private FiscalYear year;
    private TransactionalPosting posting;
    private String cash;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.UNREGISTERED, null, "27",
                LocalDate.of(2017, 7, 1), RULES);
        registers = new Registers(transactions);
        FiscalYears years = new FiscalYears(transactions, guard, clock);
        year = years.years().get(0);
        posting = new TransactionalPosting(guard, clock);
        cash = scalar("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'");
        String rent = ledger("Rent", "INDIRECT_EXPENSES");
        String furniture = ledger("Furniture", "FIXED_ASSETS");
        SalesInvoicing sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        PurchaseBilling purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        Parties parties = new Parties(transactions);
        Party customer = parties.create("Mehta Stores", PartyRole.CUSTOMER, null, 0);
        Party vendor = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);

        years.postOpening(List.of(OpeningBalance.debit(cash, d("100000"))));
        Document bill = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                vendor.id(), MAY_10, List.of(LineRequest.text("Goods", d("1"), d("30000"))))).id());
        purchases.settlements().record(SettlementRequest.of(vendor.id(), MAY_10, d("30000"), cash, PaymentMode.CASH,
                List.of(new AllocationRequest(bill.id(), d("30000")))));
        Document invoice = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                MAY_10, List.of(LineRequest.text("Goods", d("1"), d("50000"))))).id());
        sales.post(sales.createDraft(DraftRequest.note(DocumentKind.CREDIT_NOTE, customer.id(), MAY_10.plusDays(1),
                invoice.id(), List.of(LineRequest.text("Goods returned", d("1"), d("5000"))))).id());
        journal(rent, cash, "5000");
        journal(furniture, cash, "8000");
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

    private String ledger(String name, String group) throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        transactions.inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO ledger (id, group_id, name, created_at, updated_at) SELECT '" + id
                        + "', id, '" + name + "', 0, 0 FROM ledger_group WHERE system_code = '" + group + "'");
            }
            return null;
        });
        return id;
    }

    private void journal(String debit, String credit, String amount) throws Exception {
        String journal = scalar("SELECT id FROM voucher_type WHERE code = 'JOURNAL'");
        transactions.inTransaction(c -> posting.postWithin(c, new PostingRequest(FiscalYears.ACCOUNTING, year.id(),
                journal, MAY_10, null, "Journal", List.of(EntryLine.debit(debit, d(amount)),
                EntryLine.credit(credit, d(amount))), "j:" + debit + credit)));
    }

    private static List<Object> column(Table table, String name) {
        int i = table.columns().stream().map(Table.Column::name).toList().indexOf(name);
        return table.rows().stream().map(r -> r.get(i)).toList();
    }

    private static BigDecimal sum(List<Object> cells) {
        return cells.stream().filter(BigDecimal.class::isInstance).map(BigDecimal.class::cast)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("day book: every entry of the year, debits equal credits")
    void dayBook() {
        Table book = registers.dayBook(year.startDate(), year.endDate());

        // 1,00,000 opening + 30,000 bill + 30,000 payment + 50,000 invoice + 5,000 note + 5,000 + 8,000 journals.
        assertThat(sum(column(book, "Debit"))).isEqualByComparingTo("228000");
        assertThat(sum(column(book, "Credit"))).isEqualByComparingTo("228000");
        assertThat(column(book, "Date").get(0)).as("the opening voucher comes first").isEqualTo(APRIL_1);
        assertThat(registers.dayBook(MAY_1, MAY_10).rows()).as("the 1 April opening and the 11 May note are outside")
                .hasSize(book.rows().size() - 4);
    }

    @Test
    @DisplayName("ledger account: cash brought forward 1,00,000, then the month's payments in posting order")
    void ledgerAccount() {
        Table account = registers.ledgerAccount(cash, MAY_1, MAY_31);

        assertThat(column(account, "Narration").get(0)).isEqualTo("Balance brought forward");
        assertThat(column(account, "Balance")).extracting(v -> ((BigDecimal) v).toPlainString())
                .containsExactly("100000.00", "70000.00", "65000.00", "57000.00");
        assertThat(column(account, "Credit")).extracting(v -> v == null ? null : ((BigDecimal) v).toPlainString())
                .containsExactly(null, "30000.00", "5000.00", "8000.00");
        assertThat(registers.ledgerAccount(cash, APRIL_1, APRIL_1).rows())
                .as("from the first day, the opening voucher is an entry, not a balance brought forward")
                .extracting(r -> r.get(6).toString()).containsExactly("0.00", "100000.00");
    }

    @Test
    @DisplayName("an unknown ledger is refused")
    void unknownLedger() {
        assertThatThrownBy(() -> registers.ledgerAccount("no-such-ledger", MAY_1, MAY_31))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sales register: the invoice, and the credit note as a negative; the purchase register: the bill")
    void salesAndPurchaseRegisters() {
        Table sales = registers.salesRegister(MAY_1, MAY_31);
        Table purchases = registers.purchaseRegister(MAY_1, MAY_31);

        assertThat(column(sales, "Kind")).containsExactly("Invoice", "Credit note");
        assertThat(column(sales, "Party")).containsOnly("Mehta Stores");
        assertThat(sum(column(sales, "Total"))).isEqualByComparingTo("45000");
        assertThat(sum(column(sales, "Taxable"))).isEqualByComparingTo("45000");
        assertThat(column(purchases, "Kind")).containsExactly("Bill");
        assertThat(sum(column(purchases, "Total"))).isEqualByComparingTo("30000");
        assertThat(column(purchases, "Status")).containsExactly("Posted");
    }

    @Test
    @DisplayName("a register exports: the CSV carries the exact figures")
    void exports() {
        String csv = TableExport.csv(registers.salesRegister(MAY_1, MAY_31));

        assertThat(csv).startsWith("Date,Number,Kind,Party,GSTIN,Taxable,Tax,Total,Status\r\n")
                .contains(",50000.00,0.00,50000.00,Posted").contains(",-5000.00,0.00,-5000.00,Posted");
    }
}
