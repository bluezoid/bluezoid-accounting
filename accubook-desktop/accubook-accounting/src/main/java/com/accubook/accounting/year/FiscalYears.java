package com.accubook.accounting.year;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.ReversalRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleTransactions;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fiscal years: the next one, opening balances, closing and reopening (Phase 11).
 *
 * <h2>Closing (B-2)</h2>
 * A closing voucher on the year's last day moves every income and expense
 * ledger's balance for the year to Retained Earnings, and the year is locked -
 * by the posting core and by the schema. The next year's opening is this
 * year's closing by construction: balance-sheet ledgers carry their cumulative
 * balance, and the profit-and-loss ledgers start at nil because the close
 * emptied them. Closing is refused while the books for the year are incomplete:
 * drafts, or a missing or stale year-end stock valuation.
 *
 * <h2>Reopening (I-16)</h2>
 * Only the most recently closed year, with a reason. Its closing voucher is
 * reversed; corrections go in that year; closing again posts a fresh one.
 */
public final class FiscalYears {

    /** The kernel's own identity as an originator of postings. */
    public static final ModuleId ACCOUNTING = ModuleId.of("accounting");

    private final ModuleTransactions transactions;
    private final TransactionalPosting posting;
    private final Clock clock;

    public FiscalYears(ModuleTransactions transactions, EntitlementGuard entitlement, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.posting = new TransactionalPosting(Objects.requireNonNull(entitlement, "entitlement"), clock);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Every fiscal year, newest first. */
    public List<FiscalYear> years() {
        return read(TrialBalanceQuery::fiscalYears);
    }

    /**
     * Create the year after the latest one. Its length follows the latest year,
     * so the calendar comes from the company's own data, never from a fixed
     * April-March (D-32).
     */
    public FiscalYear createNext() {
        String[] id = {null};
        write(c -> id[0] = createNext(c));
        return years().stream().filter(y -> y.id().equals(id[0])).findFirst().orElseThrow();
    }

    /**
     * P11-1: the opening voucher, dated the first day of the first year. A
     * difference between the debits and credits given goes to Opening Balance
     * Equity and is reported, so it can be investigated.
     */
    public OpeningResult postOpening(List<OpeningBalance> balances) {
        if (balances.isEmpty()) {
            throw new YearEndException("Give at least one opening balance");
        }
        String[] voucher = {null};
        BigDecimal[] difference = {BigDecimal.ZERO};
        write(c -> {
            List<FiscalYear> years = TrialBalanceQuery.fiscalYears(c);
            FiscalYear first = years.get(years.size() - 1);
            if (events(c, first.id(), "OPENING") > 0) {
                throw new YearEndException("The opening voucher is already posted; reverse it to post another");
            }
            List<EntryLine> entries = new ArrayList<>();
            BigDecimal net = BigDecimal.ZERO;
            for (OpeningBalance b : balances) {
                if (b.debit().signum() == 0 && b.credit().signum() == 0) {
                    continue;
                }
                entries.add(new EntryLine(b.ledgerId(), b.debit(), b.credit(), "Opening balance"));
                net = net.add(b.debit()).subtract(b.credit());
            }
            if (net.signum() != 0) {
                String equity = ledger(c, "OPENING_BALANCE_EQUITY");
                entries.add(net.signum() > 0 ? EntryLine.credit(equity, net) : EntryLine.debit(equity, net.negate()));
            }
            difference[0] = net;
            PostedVoucher v = posting.postWithin(c, new PostingRequest(ACCOUNTING, first.id(), journal(c),
                    first.startDate(), null, "Opening balances", entries, "opening:" + first.id()));
            voucher[0] = v.voucherId();
            event(c, first.id(), "OPENING", v.voucherId(), null, null);
        });
        return new OpeningResult(voucher[0], difference[0]);
    }

    /**
     * B-2: close a year. Refused while it has drafts or, where stock moved, its
     * year-end valuation is missing or stale. Posts the closing voucher, locks
     * the year, and creates the next year if there is none.
     *
     * @return the closing voucher's id, or null if every income and expense
     *         ledger was already nil
     */
    public String close(String fiscalYearId, String actor) {
        String[] voucher = {null};
        write(c -> {
            FiscalYear year = year(c, fiscalYearId);
            if (year.closed()) {
                throw new YearEndException(year.name() + " is already closed");
            }
            requireComplete(c, year);
            List<EntryLine> entries = new ArrayList<>();
            BigDecimal profit = BigDecimal.ZERO;
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT e.ledger_id, SUM(e.debit_amount_e4) - SUM(e.credit_amount_e4)
                    FROM voucher_entry e
                    JOIN voucher v ON v.id = e.voucher_id
                    JOIN ledger l ON l.id = e.ledger_id
                    JOIN ledger_group g ON g.id = l.group_id
                    WHERE v.fiscal_year_id = ? AND v.is_posted = 1 AND g.nature IN ('INCOME', 'EXPENSES')
                    GROUP BY e.ledger_id
                    HAVING SUM(e.debit_amount_e4) - SUM(e.credit_amount_e4) <> 0
                    ORDER BY e.ledger_id
                    """)) {
                ps.setString(1, year.id());
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        BigDecimal net = MoneyMapper.fromStored(r.getLong(2), Scale.MONEY);
                        // Each ledger is emptied: a debit balance is credited, a credit balance debited.
                        entries.add(net.signum() > 0 ? EntryLine.credit(r.getString(1), net)
                                : EntryLine.debit(r.getString(1), net.negate()));
                        profit = profit.subtract(net);
                    }
                }
            }
            if (!entries.isEmpty()) {
                String retained = ledger(c, "RETAINED_EARNINGS");
                if (profit.signum() != 0) {
                    entries.add(profit.signum() > 0 ? EntryLine.credit(retained, profit)
                            : EntryLine.debit(retained, profit.negate()));
                }
                PostedVoucher v = posting.postWithin(c, new PostingRequest(ACCOUNTING, year.id(), journal(c),
                        year.endDate(), null, "Closing entry for " + year.name() + ": profit and loss to "
                        + "Retained Earnings", entries, "close:" + year.id() + ":" + events(c, year.id(), "CLOSE")));
                voucher[0] = v.voucherId();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE fiscal_year SET is_closed = 1, closed_at = ?, "
                    + "closed_by = ? WHERE id = ?")) {
                ps.setLong(1, clock.millis());
                ps.setString(2, actor);
                ps.setString(3, year.id());
                ps.executeUpdate();
            }
            event(c, year.id(), "CLOSE", voucher[0], null, actor);
            if (TrialBalanceQuery.fiscalYears(c).stream().noneMatch(y -> y.startDate().isAfter(year.startDate()))) {
                createNext(c);
            }
        });
        return voucher[0];
    }

    /**
     * I-16: reopen the most recently closed year, with a reason. Its closing
     * voucher is reversed in that year; the year is open for corrections and
     * closes again with a fresh closing voucher.
     */
    public void reopen(String fiscalYearId, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new YearEndException("Reopening a closed year needs a reason; it stays on the record");
        }
        write(c -> {
            FiscalYear year = year(c, fiscalYearId);
            if (!year.closed()) {
                throw new YearEndException(year.name() + " is not closed");
            }
            if (TrialBalanceQuery.fiscalYears(c).stream()
                    .anyMatch(y -> y.closed() && y.startDate().isAfter(year.startDate()))) {
                throw new YearEndException("A later year is closed; only the most recently closed year reopens");
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE fiscal_year SET is_closed = 0, closed_at = NULL, "
                    + "closed_by = NULL WHERE id = ?")) {
                ps.setString(1, year.id());
                ps.executeUpdate();
            }
            String closing = lastClosingVoucher(c, year.id());
            String reversal = closing == null ? null : posting.reverseWithin(c, closing, new ReversalRequest(
                    ACCOUNTING, year.id(), year.endDate(), "Year reopened: " + reason.strip(), actor,
                    "reopen:" + year.id() + ":" + events(c, year.id(), "REOPEN"))).voucherId();
            event(c, year.id(), "REOPEN", reversal, reason.strip(), actor);
        });
    }

    // ================================================================ helpers

    /** The books for the year must be complete before they close. */
    private static void requireComplete(Connection c, FiscalYear year) throws SQLException {
        List<String> problems = new ArrayList<>();
        int vouchers = count(c, "SELECT COUNT(*) FROM voucher WHERE fiscal_year_id = ? AND is_posted = 0 "
                + "AND status NOT IN ('REJECTED', 'CANCELLED')", year.id());
        if (vouchers > 0) {
            problems.add(vouchers + " voucher(s) not posted");
        }
        for (String side : List.of("sales", "purchase")) {
            int drafts = count(c, "SELECT COUNT(*) FROM " + side + "_document WHERE status = 'DRAFT' "
                    + "AND document_date BETWEEN (SELECT start_date FROM fiscal_year WHERE id = ?1) "
                    + "AND (SELECT end_date FROM fiscal_year WHERE id = ?1)", year.id());
            if (drafts > 0) {
                problems.add(drafts + " draft " + side + " document(s)");
            }
        }
        int moved = count(c, "SELECT COUNT(*) FROM stock_movement WHERE movement_date <= "
                + "(SELECT end_date FROM fiscal_year WHERE id = ?)", year.id());
        if (moved > 0 && count(c, "SELECT COUNT(*) FROM valuation_run WHERE status = 'CURRENT' AND run_date = "
                + "(SELECT end_date FROM fiscal_year WHERE id = ?)", year.id()) == 0) {
            problems.add("no current closing-stock valuation on " + year.endDate()
                    + " - post one so the year's closing stock is in the books");
        }
        if (!problems.isEmpty()) {
            throw new YearEndException(year.name() + " cannot close yet: " + String.join("; ", problems));
        }
    }

    private static String createNext(Connection c) throws SQLException {
        List<FiscalYear> years = TrialBalanceQuery.fiscalYears(c);
        FiscalYear latest = years.get(0);
        LocalDate start = latest.endDate().plusDays(1);
        LocalDate end = start.plusYears(1).minusDays(1);
        String name = start.getMonthValue() == 1 ? "FY " + start.getYear()
                : "FY " + start.getYear() + "-" + String.format("%02d", (start.getYear() + 1) % 100);
        String id = Ids.newIdString();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fiscal_year (id, name, start_date, end_date, "
                + "created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, name);
            ps.setString(3, start.toString());
            ps.setString(4, end.toString());
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
        return id;
    }

    private static FiscalYear year(Connection c, String id) throws SQLException {
        return TrialBalanceQuery.fiscalYears(c).stream().filter(y -> y.id().equals(id)).findFirst()
                .orElseThrow(() -> new YearEndException("No such fiscal year: " + id));
    }

    private static String lastClosingVoucher(Connection c, String yearId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT voucher_id FROM fiscal_year_event WHERE "
                + "fiscal_year_id = ? AND action = 'CLOSE' ORDER BY at DESC, rowid DESC LIMIT 1")) {
            ps.setString(1, yearId);
            try (ResultSet r = ps.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    private void event(Connection c, String yearId, String action, String voucherId, String reason, String actor)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO fiscal_year_event (id, fiscal_year_id, action, "
                + "voucher_id, reason, actor, at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, Ids.newIdString());
            ps.setString(2, yearId);
            ps.setString(3, action);
            ps.setString(4, voucherId);
            ps.setString(5, reason);
            ps.setString(6, actor);
            ps.setLong(7, clock.millis());
            ps.executeUpdate();
        }
    }

    private static int events(Connection c, String yearId, String action) throws SQLException {
        return count(c, "SELECT COUNT(*) FROM fiscal_year_event WHERE fiscal_year_id = ?1 AND action = '" + action
                + "'", yearId);
    }

    private static int count(Connection c, String sql, String parameter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    private static String ledger(Connection c, String code) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM ledger WHERE system_code = ?")) {
            ps.setString(1, code);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new YearEndException("The " + code + " ledger is missing from this company");
                }
                return r.getString(1);
            }
        }
    }

    private static String journal(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM voucher_type WHERE code = 'JOURNAL'");
             ResultSet r = ps.executeQuery()) {
            if (!r.next()) {
                throw new YearEndException("The journal voucher type is missing");
            }
            return r.getString(1);
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(Connection connection) throws SQLException;
    }

    private void write(Work work) {
        try {
            transactions.inTransaction(connection -> {
                work.run(connection);
                return null;
            });
        } catch (SQLException e) {
            throw new YearEndException(e.getMessage(), e);
        }
    }

    private <T> T read(ModuleTransactions.Work<T> work) {
        try {
            return transactions.read(work);
        } catch (SQLException e) {
            throw new YearEndException("Could not read fiscal years: " + e.getMessage(), e);
        }
    }
}
