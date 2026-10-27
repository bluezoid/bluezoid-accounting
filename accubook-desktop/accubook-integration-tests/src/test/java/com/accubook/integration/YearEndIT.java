package com.accubook.integration;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostingException;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.accounting.report.FinancialStatements;
import com.accubook.accounting.year.FiscalYears;
import com.accubook.accounting.year.OpeningBalance;
import com.accubook.accounting.year.OpeningResult;
import com.accubook.accounting.year.YearEndException;
import com.accubook.app.company.CompanyService;
import com.accubook.inventory.Inventory;
import com.accubook.inventory.InventoryModule;
import com.accubook.inventory.ItemKind;
import com.accubook.inventory.MovementRequest;
import com.accubook.inventory.MovementType;
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
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 11 exit: close year 1, see year 2 open with year 1's closing, and have
 * a back-dated posting into year 1 refused. Then reopen, correct, close again.
 */
@DisplayName("Year-end")
class YearEndIT {

    private static final RulePack RULES = RulePacks.bundledPack();
    private static final LocalDate MAY_10 = LocalDate.of(2026, 5, 10);

    @TempDir
    Path dir;

    private CompanyService service;
    private ModuleTransactions transactions;
    private EntitlementGuard guard;
    private FiscalYears years;
    private SalesInvoicing sales;
    private PurchaseBilling purchases;
    private Party customer;
    private Party vendor;
    private String cash;
    private FiscalYear year1;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())),
                id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        guard = new EntitlementGuard(service.moduleStates());
        Clock clock = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);
        new GstRegistrations(transactions, clock).register(GstRegistration.Scheme.UNREGISTERED, null, "27",
                LocalDate.of(2017, 7, 1), RULES);
        years = new FiscalYears(transactions, guard, clock);
        sales = new SalesInvoicing(transactions, guard, Optional.empty(), () -> RULES, clock);
        purchases = new PurchaseBilling(transactions, guard, Optional.empty(), () -> RULES, clock);
        Parties parties = new Parties(transactions);
        customer = parties.create("Mehta Stores", PartyRole.CUSTOMER, null, 0);
        vendor = parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);
        cash = scalar("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'");
        year1 = years.years().get(0);
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

    /** A ledger's balance, debit positive, from every posted voucher dated up to {@code on}. */
    private BigDecimal balance(String systemCodeOrName, LocalDate on) throws Exception {
        String sum = scalar("SELECT COALESCE(SUM(e.debit_amount_e4 - e.credit_amount_e4), 0) FROM voucher_entry e "
                + "JOIN voucher v ON v.id = e.voucher_id JOIN ledger l ON l.id = e.ledger_id WHERE v.is_posted = 1 "
                + "AND v.voucher_date <= '" + on + "' AND (l.system_code = '" + systemCodeOrName + "' OR l.name = '"
                + systemCodeOrName + "')");
        return new BigDecimal(sum).movePointLeft(4);
    }

    /** A year of trade: sales 50,000, purchases 30,000 - a profit of 20,000. */
    private void yearOfTrade() {
        sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Consulting", d("1"), d("50000"))))).id());
        purchases.post(purchases.createDraft(DraftRequest.of(DocumentKind.PURCHASE_BILL, vendor.id(), MAY_10,
                List.of(LineRequest.text("Supplies", d("1"), d("30000"))))).id());
    }

    @Test
    @DisplayName("P11-1: the opening voucher; an unbalanced opening goes to Opening Balance Equity, and only once")
    void opening() throws Exception {
        OpeningResult r = years.postOpening(List.of(OpeningBalance.debit(cash, d("100000"))));

        assertThat(r.balanced()).isFalse();
        assertThat(r.difference()).isEqualByComparingTo("100000");
        assertThat(balance("CASH_IN_HAND", year1.startDate())).isEqualByComparingTo("100000");
        assertThat(balance("OPENING_BALANCE_EQUITY", year1.startDate())).isEqualByComparingTo("-100000");
        assertThatThrownBy(() -> years.postOpening(List.of(OpeningBalance.debit(cash, d("1")))))
                .hasMessageContaining("already posted");
    }

    @Test
    @DisplayName("exit: close year 1 - profit to Retained Earnings, year 2 opens from year 1's closing, year 1 locked")
    void closeYear() throws Exception {
        years.postOpening(List.of(OpeningBalance.debit(cash, d("100000"))));
        yearOfTrade();

        String closing = years.close(year1.id(), "owner");

        assertThat(closing).isNotNull();
        LocalDate end = year1.endDate();
        assertThat(balance("SALES", end)).as("income emptied").isEqualByComparingTo("0");
        assertThat(balance("PURCHASES", end)).as("expenses emptied").isEqualByComparingTo("0");
        assertThat(balance("RETAINED_EARNINGS", end)).as("profit").isEqualByComparingTo("-20000");
        FiscalYear year2 = years.years().get(0);
        assertThat(year2.startDate()).isEqualTo(end.plusDays(1));
        assertThat(years.years().get(1).closed()).isTrue();
        // Year 2 opens with year 1's closing: balance-sheet ledgers carry over, P&L ledgers start at nil.
        assertThat(balance("Mehta Stores", year2.startDate())).isEqualByComparingTo(balance("Mehta Stores", end));
        assertThat(balance("RETAINED_EARNINGS", year2.startDate())).isEqualByComparingTo("-20000");

        Document late = sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Late", d("1"), d("100")))));
        assertThatThrownBy(() -> sales.post(late.id())).isInstanceOf(TradeException.class)
                .hasMessageContaining("closed");
        TransactionalPosting posting = new TransactionalPosting(guard);
        String journal = scalar("SELECT id FROM voucher_type WHERE code = 'JOURNAL'");
        String salesLedger = scalar("SELECT id FROM ledger WHERE system_code = 'SALES'");
        assertThatThrownBy(() -> transactions.inTransaction(c -> posting.postWithin(c, new PostingRequest(
                FiscalYears.ACCOUNTING, year1.id(), journal, MAY_10, null, "Back-dated",
                List.of(EntryLine.debit(cash, d("1")), EntryLine.credit(salesLedger, d("1"))), "late"))))
                .isInstanceOf(PostingException.class).hasMessageContaining("is closed");
    }

    @Test
    @DisplayName("a voucher dated outside its fiscal year is refused by the posting core")
    void outsideTheYear() throws Exception {
        TransactionalPosting posting = new TransactionalPosting(guard);
        String journal = scalar("SELECT id FROM voucher_type WHERE code = 'JOURNAL'");
        String salesLedger = scalar("SELECT id FROM ledger WHERE system_code = 'SALES'");

        assertThatThrownBy(() -> transactions.inTransaction(c -> posting.postWithin(c, new PostingRequest(
                FiscalYears.ACCOUNTING, year1.id(), journal, year1.endDate().plusDays(1), null, "Wrong year",
                List.of(EntryLine.debit(cash, d("1")), EntryLine.credit(salesLedger, d("1"))), "x"))))
                .isInstanceOf(PostingException.class).hasMessageContaining("is outside fiscal year");
    }

    @Test
    @DisplayName("closing is refused while the year has drafts, or stock moved without a year-end valuation")
    void incompleteBooks() throws Exception {
        Document draft = sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Pending", d("1"), d("100")))));
        assertThatThrownBy(() -> years.close(year1.id(), "owner")).isInstanceOf(YearEndException.class)
                .hasMessageContaining("1 draft sales document");
        sales.deleteDraft(draft.id());

        Inventory inventory = new Inventory(transactions, guard);
        var unit = inventory.createUnit("NOS", "Numbers", 0);
        var item = inventory.createItem("Lamp", ItemKind.GOODS, unit.id(), null, null, null, null);
        var main = inventory.createWarehouse("Main", null);
        inventory.record(MovementRequest.in(MovementType.PURCHASE, item.id(), main.id(), d("5"), d("100"), MAY_10,
                null));
        assertThatThrownBy(() -> years.close(year1.id(), "owner")).hasMessageContaining("closing-stock valuation");

        inventory.postValuation(year1.endDate());
        years.close(year1.id(), "owner");
        assertThat(balance("Stock-in-Hand", year1.endDate())).as("closing stock in the books")
                .isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("I-16: reopen the latest closed year with a reason, correct it, and close again")
    void reopen() throws Exception {
        yearOfTrade();
        years.close(year1.id(), "owner");

        assertThatThrownBy(() -> years.reopen(year1.id(), " ", "owner")).hasMessageContaining("needs a reason");
        years.reopen(year1.id(), "Missed a sale", "owner");

        assertThat(balance("SALES", year1.endDate())).as("closing reversed").isEqualByComparingTo("-50000");
        sales.post(sales.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, customer.id(), MAY_10,
                List.of(LineRequest.text("Missed", d("1"), d("5000"))))).id());
        years.close(year1.id(), "owner");
        assertThat(balance("RETAINED_EARNINGS", year1.endDate())).isEqualByComparingTo("-25000");
        assertThat(balance("SALES", year1.endDate())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("years close in order and reopen latest first")
    void order() {
        FiscalYear year2 = years.createNext();
        assertThatThrownBy(() -> years.close(year2.id(), "owner")).hasMessageContaining("earlier fiscal years first");

        years.close(year1.id(), "owner");
        years.close(year2.id(), "owner");

        assertThatThrownBy(() -> years.reopen(year1.id(), "Correction", "owner"))
                .hasMessageContaining("only the most recently closed year");
    }

    @Test
    @DisplayName("S2-3: the opening is replaced with a reason while year 1 is open - twice if need be - never after")
    void replaceOpening() throws Exception {
        years.postOpening(List.of(OpeningBalance.debit(cash, d("100000"))));
        assertThatThrownBy(() -> years.replaceOpening(List.of(OpeningBalance.debit(cash, d("90000"))), " ", "owner"))
                .hasMessageContaining("needs a reason");

        years.replaceOpening(List.of(OpeningBalance.debit(cash, d("90000"))), "Cash was counted wrong", "owner");
        years.replaceOpening(List.of(OpeningBalance.debit(cash, d("95000"))), "Recounted again", "owner");

        assertThat(balance("CASH_IN_HAND", year1.startDate())).isEqualByComparingTo("95000");
        assertThat(balance("OPENING_BALANCE_EQUITY", year1.startDate())).isEqualByComparingTo("-95000");
        assertThat(scalar("SELECT COUNT(*) FROM fiscal_year_event WHERE action = 'OPENING' AND reason = "
                + "'Recounted again'")).isEqualTo("2");
        assertThat(new FinancialStatements(transactions).cashFlow(year1.startDate(), year1.endDate()).openingCash())
                .as("replaced openings and their reversals are not flows").isEqualByComparingTo("95000");

        years.close(year1.id(), "owner");
        assertThatThrownBy(() -> years.replaceOpening(List.of(OpeningBalance.debit(cash, d("1"))), "Late", "owner"))
                .isInstanceOf(YearEndException.class).hasMessageContaining("is closed");
    }

    @Test
    @DisplayName("S2-3: nothing to replace before the opening is posted")
    void nothingToReplace() {
        assertThatThrownBy(() -> years.replaceOpening(List.of(OpeningBalance.debit(cash, d("1"))), "Why", "owner"))
                .hasMessageContaining("no opening balances to replace");
    }

    @Test
    @DisplayName("S2-4: a party's ledger is not an opening line; its unpaid bills are")
    void noPartyLines() {
        assertThatThrownBy(() -> years.postOpening(List.of(OpeningBalance.debit(customer.ledgerId(), d("500")))))
                .isInstanceOf(YearEndException.class).hasMessageContaining("Mehta Stores is a party");
    }

    @Test
    @DisplayName("S2-5: Stock-in-Hand is the item-wise opening stock, computed to the paisa, never typed")
    void openingStock() throws Exception {
        Inventory inventory = new Inventory(transactions, guard);
        var unit = inventory.createUnit("NOS", "Numbers", 0);
        var lamp = inventory.createItem("Lamp", ItemKind.GOODS, unit.id(), null, null, null, null);
        var main = inventory.createWarehouse("Main", null);
        // 3 x 33.3333 = 99.9999, which the valuation posts as 100.00.
        inventory.record(MovementRequest.in(MovementType.OPENING, lamp.id(), main.id(), d("3"), d("33.3333"),
                year1.startDate(), null));

        years.postOpening(List.of(OpeningBalance.debit(cash, d("1000"))));

        assertThat(balance("STOCK_IN_HAND", year1.startDate())).isEqualByComparingTo("100.00");
        assertThat(balance("OPENING_BALANCE_EQUITY", year1.startDate())).isEqualByComparingTo("-1100");
        String stockLedger = scalar("SELECT id FROM ledger WHERE system_code = 'STOCK_IN_HAND'");
        assertThatThrownBy(() -> years.replaceOpening(List.of(OpeningBalance.debit(stockLedger, d("100"))), "Typed",
                "owner")).hasMessageContaining("not typed");

        // Opening stock found later: the books no longer carry it, so year 1 will not close until replaced.
        inventory.record(MovementRequest.in(MovementType.OPENING, lamp.id(), main.id(), d("2"), d("50"),
                year1.startDate(), null));
        inventory.postValuation(year1.endDate());
        assertThatThrownBy(() -> years.close(year1.id(), "owner"))
                .hasMessageContaining("do not carry the opening stock now entered (200.00)");

        years.replaceOpening(List.of(OpeningBalance.debit(cash, d("1000"))), "Opening stock found", "owner");
        inventory.postValuation(year1.endDate());
        years.close(year1.id(), "owner");
        assertThat(balance("Change in Stock", year1.endDate())).as("no trade: stock unchanged, no profit from it")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the opening position shows what was entered - not the computed stock nor the balancing line")
    void openingPosition() throws Exception {
        assertThat(years.openingPosition().posted()).isFalse();
        assertThat(years.openingPosition().booksBegin()).isEqualTo(year1.startDate());
        Inventory inventory = new Inventory(transactions, guard);
        var unit = inventory.createUnit("NOS", "Numbers", 0);
        var lamp = inventory.createItem("Lamp", ItemKind.GOODS, unit.id(), null, null, null, null);
        var main = inventory.createWarehouse("Main", null);
        inventory.record(MovementRequest.in(MovementType.OPENING, lamp.id(), main.id(), d("2"), d("50"),
                year1.startDate(), null));

        years.postOpening(List.of(OpeningBalance.debit(cash, d("1000"))));

        var position = years.openingPosition();
        assertThat(position.posted()).isTrue();
        assertThat(position.lines()).singleElement().satisfies(l -> {
            assertThat(l.ledgerId()).isEqualTo(cash);
            assertThat(l.debit()).isEqualByComparingTo("1000");
        });
        assertThat(position.openingStock()).isEqualByComparingTo("100.00");
        assertThat(position.editable()).isTrue();
        inventory.postValuation(year1.endDate());
        years.close(year1.id(), "owner");
        assertThat(years.openingPosition().editable()).isFalse();
    }
}
