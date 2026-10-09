package com.accubook.accounting.chart;

import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.PostingService;
import com.accubook.accounting.posting.ReversalRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 6 exit criterion, end to end.
 *
 * <p>company → chart of accounts → ledgers → voucher type → posting →
 * trial balance → reversal → trial balance still correct.
 *
 * <p>Every posting here goes through {@link PostingService}. Nothing in this
 * suite writes {@code voucher} or {@code voucher_entry}, because nothing can.
 */
class BookkeepingCycleIT {

    @TempDir
    Path directory;

    private ChartFixture fixture;
    private ChartOfAccounts chart;
    private PostingService posting;
    private String fiscalYear;
    private String journalType;

    private static final BigDecimal THOUSAND = new BigDecimal("1000.00");

    @BeforeEach
    void setUp() throws SQLException {
        fixture = new ChartFixture(directory);
        chart = fixture.chart();
        posting = new PostingService(fixture.database(), fixture.entitlement());
        fiscalYear = fixture.fiscalYearId();
        journalType = fixture.voucherTypeId("JOURNAL");
    }

    @AfterEach
    void tearDown() throws SQLException {
        fixture.close();
    }

    private PostedVoucher post(String debitLedger, String creditLedger,
                               BigDecimal amount, LocalDate date) {
        return posting.post(new PostingRequest(ChartFixture.ACCOUNTING, fiscalYear, journalType,
                date, null, "Test entry",
                List.of(EntryLine.debit(debitLedger, amount),
                        EntryLine.credit(creditLedger, amount)),
                null));
    }

    @Test
    @DisplayName("a freshly provisioned company has a balanced, empty trial balance")
    void freshCompanyBalances() {
        TrialBalance tb = chart.trialBalance(fiscalYear);

        // A-8: zero rows totalling 0 = 0 is a legitimate *report*. This is
        // not the A-1 degenerate-voucher case, where 0 = 0 was a meaningless
        // transaction.
        assertAll(
                () -> assertTrue(tb.balances()),
                () -> assertEquals(chart.allAccounts().size(), tb.rows().size(),
                        "every account appears, even with no movement"),
                () -> assertTrue(tb.nonZeroRows().isEmpty()),
                () -> assertEquals(0, tb.totalDebit().signum()));
    }

    @Test
    @DisplayName("the full cycle: post, verify, reverse, verify again")
    void fullBookkeepingCycle() throws SQLException {
        String cash = fixture.ledgerIdBySystemCode("CASH_IN_HAND");
        String capital = fixture.ledgerIdBySystemCode("OPENING_BALANCE_EQUITY");

        // 1. Post capital introduced: Dr Cash, Cr Opening Balance Equity.
        PostedVoucher voucher = post(cash, capital, THOUSAND, LocalDate.of(2026, 4, 1));

        TrialBalance afterPosting = chart.trialBalance(fiscalYear);
        assertAll(
                () -> assertTrue(afterPosting.balances(), "must balance after posting"),
                () -> assertEquals(2, afterPosting.nonZeroRows().size()),
                () -> assertEquals(0, afterPosting.totalDebit().compareTo(THOUSAND)),
                () -> assertEquals(0, afterPosting.totalCredit().compareTo(THOUSAND)));

        // 2. Cash is debit-natured, so it nets to the debit column.
        TrialBalanceRow cashRow = afterPosting.rows().stream()
                .filter(r -> r.ledgerId().equals(cash)).findFirst().orElseThrow();
        assertAll(
                () -> assertEquals(0, cashRow.debit().compareTo(THOUSAND)),
                () -> assertEquals(0, cashRow.credit().signum(), "one side only"));

        // 3. Reverse it.
        posting.reverse(voucher.voucherId(), new ReversalRequest(ChartFixture.ACCOUNTING,
                fiscalYear, LocalDate.of(2026, 5, 1), "entered in error", "tester", null));

        // 4. Original and contra are both in the books and cancel exactly.
        TrialBalance afterReversal = chart.trialBalance(fiscalYear);
        assertAll(
                () -> assertTrue(afterReversal.balances(), "must still balance after reversal"),
                () -> assertEquals(0, afterReversal.totalDebit().signum(),
                        "a reversal in the same year nets the accounts back to zero"),
                () -> assertEquals(chart.allAccounts().size(), afterReversal.rows().size(),
                        "every account still appears"));
    }

    @Test
    @DisplayName("a REVERSED voucher still counts - it is posted, not erased")
    void reversedVoucherStillCounts() throws SQLException {
        String cash = fixture.ledgerIdBySystemCode("CASH_IN_HAND");
        String capital = fixture.ledgerIdBySystemCode("OPENING_BALANCE_EQUITY");

        PostedVoucher voucher = post(cash, capital, THOUSAND, LocalDate.of(2026, 4, 1));
        posting.reverse(voucher.voucherId(), new ReversalRequest(ChartFixture.ACCOUNTING,
                fiscalYear, LocalDate.of(2026, 5, 1), "correction", "tester", null));

        // P6-T1. The trial balance filters on is_posted alone. If it named a
        // status instead - as it once did with 'APPROVED' - the P5 lifecycle
        // change would have silently zeroed every balance. Worse, excluding
        // REVERSED here would leave the original's effect in the books with
        // nothing opposing it.
        assertAll(
                () -> assertEquals("REVERSED", fixture.scalar(
                        "SELECT status FROM voucher WHERE id = '" + voucher.voucherId() + "'")),
                () -> assertEquals(2, fixture.count("voucher")),
                () -> assertEquals(4, fixture.count("voucher_entry")),
                () -> assertTrue(chart.trialBalance(fiscalYear).balances()));
    }

    @Test
    @DisplayName("A-10: a reversal in a later fiscal year leaves the original year unbalanced")
    void crossYearReversalLeavesOriginalYearOpen() throws SQLException {
        String cash = fixture.ledgerIdBySystemCode("CASH_IN_HAND");
        String capital = fixture.ledgerIdBySystemCode("OPENING_BALANCE_EQUITY");

        // A second fiscal year, so the reversal can land outside the first.
        String nextYear = com.accubook.platform.id.Ids.newIdString();
        fixture.database().inUnguardedTransaction(connection -> {
            try (var ps = connection.prepareStatement(
                    "INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                            + "VALUES (?, 'FY 2027-28', '2027-04-01', '2028-03-31', 0)")) {
                ps.setString(1, nextYear);
                ps.executeUpdate();
            }
            return null;
        });

        PostedVoucher voucher = post(cash, capital, THOUSAND, LocalDate.of(2026, 4, 1));
        posting.reverse(voucher.voucherId(), new ReversalRequest(ChartFixture.ACCOUNTING,
                nextYear, LocalDate.of(2027, 6, 1), "prior period correction", "tester", null));

        TrialBalance year1 = chart.trialBalance(fiscalYear);
        TrialBalance year2 = chart.trialBalance(nextYear);

        // This is correct, not a defect: the correction belongs to the year it
        // was made in. Year 1 keeps the entry it originally reported, and each
        // year's own trial balance still balances internally. Whether a prior
        // period may instead be restated is P11's question, not P6's.
        assertAll(
                () -> assertTrue(year1.balances(), "year 1 balances internally"),
                () -> assertTrue(year2.balances(), "year 2 balances internally"),
                () -> assertEquals(0, year1.totalDebit().compareTo(THOUSAND),
                        "year 1 still reports the original entry"),
                () -> assertEquals(0, year2.totalDebit().compareTo(THOUSAND),
                        "year 2 carries the correction"));
    }

    @Test
    @DisplayName("A-6: a deactivated account holding a balance stays in the trial balance")
    void deactivatedAccountWithBalanceStillReported() throws SQLException {
        String cash = fixture.ledgerIdBySystemCode("CASH_IN_HAND");
        String expense = fixture.createUserLedger("Old Expense", "INDIRECT_EXPENSES");

        post(expense, cash, THOUSAND, LocalDate.of(2026, 4, 1));
        chart.deactivate(expense, 1L);

        TrialBalance tb = chart.trialBalance(fiscalYear);
        TrialBalanceRow row = tb.rows().stream()
                .filter(r -> r.ledgerId().equals(expense)).findFirst().orElseThrow();

        // Dropping it would make the two columns disagree. Deactivation hides an
        // account from pickers, never from the books - so it is reported, and
        // flagged inactive so the reader knows why it looks unfamiliar.
        assertAll(
                () -> assertTrue(tb.balances()),
                () -> assertFalse(row.active(), "reported as inactive"),
                () -> assertEquals(0, row.debit().compareTo(THOUSAND)));
    }

    @Test
    @DisplayName("an account posted to cannot be deleted")
    void postedAccountCannotBeDeleted() throws SQLException {
        String cash = fixture.ledgerIdBySystemCode("CASH_IN_HAND");
        String expense = fixture.createUserLedger("Stationery", "INDIRECT_EXPENSES");

        post(expense, cash, THOUSAND, LocalDate.of(2026, 4, 1));

        ChartException e = org.junit.jupiter.api.Assertions.assertThrows(
                ChartException.class, () -> chart.delete(expense));

        assertTrue(e.getMessage().contains("Deactivate"), e.getMessage());
    }

    @Test
    @DisplayName("R-3: an account with postings cannot move to a different nature")
    void postedAccountCannotChangeNature() throws SQLException {
        String cash = fixture.ledgerIdBySystemCode("CASH_IN_HAND");
        String expense = fixture.createUserLedger("Misc Expense", "INDIRECT_EXPENSES");
        post(expense, cash, THOUSAND, LocalDate.of(2026, 4, 1));

        String incomeGroup = fixture.groupIdBySystemCode("SALES_ACCOUNTS");

        ChartException e = org.junit.jupiter.api.Assertions.assertThrows(ChartException.class,
                () -> chart.moveToGroup(expense, incomeGroup, 1L));

        assertAll(
                () -> assertTrue(e.getMessage().contains("opposite sign"), e.getMessage()),
                () -> assertEquals(AccountNature.EXPENSES,
                        chart.findById(expense).orElseThrow().nature()),
                () -> assertTrue(chart.trialBalance(fiscalYear).balances()));
    }

    @Test
    @DisplayName("the trial balance sums many postings correctly")
    void manyPostingsStillBalance() throws SQLException {
        String cash = fixture.ledgerIdBySystemCode("CASH_IN_HAND");
        String capital = fixture.ledgerIdBySystemCode("OPENING_BALANCE_EQUITY");
        String expense = fixture.createUserLedger("Rent", "INDIRECT_EXPENSES");

        post(cash, capital, new BigDecimal("50000.00"), LocalDate.of(2026, 4, 1));
        for (int i = 0; i < 12; i++) {
            post(expense, cash, new BigDecimal("2500.00"), LocalDate.of(2026, 4, 1).plusMonths(i));
        }

        TrialBalance tb = chart.trialBalance(fiscalYear);

        assertAll(
                () -> assertTrue(tb.balances(), "difference was " + tb.difference()),
                () -> assertEquals(0, tb.totalDebit().compareTo(new BigDecimal("50000.00"))),
                () -> assertEquals(3, tb.nonZeroRows().size()));
    }
}
