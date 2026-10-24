package com.accubook.integration;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.accounting.report.BalanceSheet;
import com.accubook.accounting.report.CashFlow;
import com.accubook.accounting.report.FinancialStatements;
import com.accubook.accounting.report.ProfitAndLoss;
import com.accubook.accounting.report.StatementLine;
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

/** Phase 11b: a year of trade into profit and loss, balance sheet and cash flow. Figures by hand. */
@DisplayName("Financial statements")
class FinancialStatementsIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private FinancialStatements statements;
    private FiscalYears years;
    private FiscalYear year;
    private TransactionalPosting posting;
    private String cash;
    private String bank;
    private String rent;
    private String furniture;
    private String loan;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        EntitlementGuard guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.UNREGISTERED, null, "27",
                LocalDate.of(2017, 7, 1), RULES);
        statements = new FinancialStatements(transactions);
        years = new FiscalYears(transactions, guard, clock);
        year = years.years().get(0);
        posting = new TransactionalPosting(guard, clock);
        cash = scalar("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'");
        bank = ledger("HDFC Current", "CASH_AND_BANK");
        rent = ledger("Rent", "INDIRECT_EXPENSES");
        furniture = ledger("Furniture", "FIXED_ASSETS");
        loan = ledger("Bank Loan", "LOANS_LIABILITY");
        SalesInvoicing sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        PurchaseBilling purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        Parties parties = new Parties(transactions);
        Party customer = parties.create("Mehta Stores", PartyRole.CUSTOMER, null, 0);
        Party vendor = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);

        years.postOpening(List.of(OpeningBalance.debit(cash, d("100000"))));          // to Opening Balance Equity
        Document bill = purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL,
                vendor.id(), MAY_10, List.of(LineRequest.text("Goods", d("1"), d("30000"))))).id());
        purchases.settlements().record(SettlementRequest.of(vendor.id(), MAY_10, d("30000"), cash, PaymentMode.CASH,
                List.of(new AllocationRequest(bill.id(), d("30000")))));
        Document invoice = sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(),
                MAY_10, List.of(LineRequest.text("Goods", d("1"), d("50000"))))).id());
        sales.settlements().record(SettlementRequest.of(customer.id(), MAY_10, d("40000"), bank, PaymentMode.NEFT,
                List.of(new AllocationRequest(invoice.id(), d("40000")))));
        journal(rent, cash, "5000");          // rent paid in cash
        journal(furniture, cash, "8000");     // furniture bought for cash
        journal(bank, loan, "20000");         // a loan into the bank
        journal(bank, cash, "10000");         // cash deposited: a transfer, not a flow
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

    private static BigDecimal amount(List<StatementLine> lines, String ledger) {
        return lines.stream().filter(l -> l.ledgerName().equals(ledger)).map(StatementLine::amount).findFirst()
                .orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("profit and loss: sales 50,000 less purchases 30,000 is gross profit 20,000; less rent 5,000")
    void profitAndLoss() {
        ProfitAndLoss pl = statements.profitAndLoss(year.startDate(), year.endDate());

        assertThat(amount(pl.tradingIncome(), "Sales")).isEqualByComparingTo("50000");
        assertThat(amount(pl.directExpenses(), "Purchases")).isEqualByComparingTo("30000");
        assertThat(pl.grossProfit()).isEqualByComparingTo("20000");
        assertThat(amount(pl.indirectExpenses(), "Rent")).isEqualByComparingTo("5000");
        assertThat(pl.netProfit()).isEqualByComparingTo("15000");
    }

    @Test
    @DisplayName("balance sheet: assets 1,35,000 = loan 20,000 + opening equity 1,00,000 + profit 15,000")
    void balanceSheet() {
        BalanceSheet bs = statements.balanceSheet(year.endDate());

        assertThat(amount(bs.assets(), "Cash in Hand")).isEqualByComparingTo("47000");
        assertThat(amount(bs.assets(), "HDFC Current")).isEqualByComparingTo("70000");
        assertThat(amount(bs.assets(), "Mehta Stores")).isEqualByComparingTo("10000");
        assertThat(amount(bs.assets(), "Furniture")).isEqualByComparingTo("8000");
        assertThat(amount(bs.liabilities(), "Bank Loan")).isEqualByComparingTo("20000");
        assertThat(amount(bs.equity(), "Opening Balance Equity")).isEqualByComparingTo("100000");
        assertThat(bs.unclosedProfit()).isEqualByComparingTo("15000");
        assertThat(bs.totalAssets()).isEqualByComparingTo("135000");
        assertThat(bs.balances()).isTrue();
    }

    @Test
    @DisplayName("after the year closes: profit and loss still shows the year's trading; the profit is in Retained Earnings")
    void afterClosing() {
        years.close(year.id(), "owner");

        assertThat(statements.profitAndLoss(year.startDate(), year.endDate()).netProfit())
                .isEqualByComparingTo("15000");
        BalanceSheet bs = statements.balanceSheet(year.endDate());
        assertThat(bs.unclosedProfit()).isEqualByComparingTo("0");
        assertThat(amount(bs.equity(), "Retained Earnings")).isEqualByComparingTo("15000");
        assertThat(bs.balances()).isTrue();
    }

    @Test
    @DisplayName("P11-2: cash flow by the direct method - operating, investing, financing; transfers and openings aside")
    void cashFlow() {
        CashFlow cf = statements.cashFlow(year.startDate(), year.endDate());

        assertThat(cf.openingCash()).as("the opening voucher is opening cash, not a flow")
                .isEqualByComparingTo("100000");
        assertThat(CashFlow.total(cf.operating())).as("+40,000 customer -30,000 vendor -5,000 rent")
                .isEqualByComparingTo("5000");
        assertThat(CashFlow.total(cf.investing())).isEqualByComparingTo("-8000");
        assertThat(CashFlow.total(cf.financing())).isEqualByComparingTo("20000");
        assertThat(cf.closingCash()).isEqualByComparingTo("117000");
        assertThat(cf.reconciles()).isTrue();
    }

    @Test
    @DisplayName("a group's classification can be changed; its cash flows move with it")
    void reclassify() throws Exception {
        statements.classify(scalar("SELECT id FROM ledger_group WHERE system_code = 'FIXED_ASSETS'"), "OPERATING");

        CashFlow cf = statements.cashFlow(year.startDate(), year.endDate());

        assertThat(cf.investing()).isEmpty();
        assertThat(CashFlow.total(cf.operating())).isEqualByComparingTo("-3000");
        assertThat(cf.reconciles()).isTrue();
    }
}
