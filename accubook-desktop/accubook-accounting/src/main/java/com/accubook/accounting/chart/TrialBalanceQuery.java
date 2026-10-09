package com.accubook.accounting.chart;

import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The trial-balance query, on a connection the caller supplies.
 *
 * <p>Separate from {@link ChartOfAccounts} so read-only callers - the UI, which
 * is only ever handed a guarded read connection - run exactly the same SQL.
 * There is one trial-balance query in the application; a second copy is how
 * {@code CompanyService.ledgerBalances} came to repeat the P6 join defect.
 */
public final class TrialBalanceQuery {

    private TrialBalanceQuery() {
    }

    /**
     * Aggregated in SQL rather than in memory, and filtered on
     * {@code is_posted = 1} alone.
     *
     * <p>That predicate matters more than it looks. {@code is_posted} is derived
     * from {@code status} by a CHECK constraint, so it is the single correct
     * test. Naming a status here instead - as this query once did with
     * {@code status = 'APPROVED'} - couples the report to a vocabulary that can
     * change, and when P5 changed it every balance would silently have become
     * zero.
     *
     * <p>A REVERSED voucher is still posted and still counts. It and its contra
     * sum to zero, which is precisely the arithmetic that makes a reversal
     * honest; excluding it would leave the original's effect in the books
     * unopposed.
     *
     * <p>{@code LEFT JOIN} throughout, so an account with no movement still
     * appears - an account that exists but has not moved is information.
     *
     * <p>The year and posted filters sit in an {@code EXISTS} against the entry,
     * not as extra conditions on a joined {@code voucher} row. Putting them on a
     * second LEFT JOIN looks equivalent and is not: the entry join would still
     * match every year, so the SUM would cover all of them while the filter
     * merely nulled the voucher columns. Measured - it made a cross-year
     * reversal cancel its own original.
     */
    public static TrialBalance compute(Connection connection, String fiscalYearId) throws SQLException {
        String sql = """
                SELECT l.id, l.name, g.nature, l.is_active,
                       COALESCE(SUM(e.debit_amount_e4), 0)  AS debit_e4,
                       COALESCE(SUM(e.credit_amount_e4), 0) AS credit_e4
                FROM ledger l
                JOIN ledger_group g ON g.id = l.group_id
                LEFT JOIN voucher_entry e ON e.ledger_id = l.id
                     AND EXISTS (SELECT 1 FROM voucher v
                                 WHERE v.id = e.voucher_id
                                   AND v.is_posted = 1
                                   AND v.fiscal_year_id = ?)
                GROUP BY l.id, l.name, g.nature, l.is_active
                ORDER BY l.name
                """;

        List<TrialBalanceRow> rows = new ArrayList<>();
        long totalDebitE4 = 0;
        long totalCreditE4 = 0;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, fiscalYearId);
            try (ResultSet result = ps.executeQuery()) {
                while (result.next()) {
                    long debitE4 = result.getLong("debit_e4");
                    long creditE4 = result.getLong("credit_e4");

                    // Net each account to a single side. A ledger showing both
                    // columns has not been summarised, it has been transcribed.
                    long netDebit = Math.max(0, debitE4 - creditE4);
                    long netCredit = Math.max(0, creditE4 - debitE4);

                    totalDebitE4 += netDebit;
                    totalCreditE4 += netCredit;

                    rows.add(new TrialBalanceRow(
                            result.getString("id"),
                            result.getString("name"),
                            AccountNature.valueOf(result.getString("nature")),
                            MoneyMapper.fromStored(netDebit, Scale.MONEY),
                            MoneyMapper.fromStored(netCredit, Scale.MONEY),
                            result.getInt("is_active") == 1));
                }
            }
        }

        return new TrialBalance(fiscalYearId, rows,
                MoneyMapper.fromStored(totalDebitE4, Scale.MONEY),
                MoneyMapper.fromStored(totalCreditE4, Scale.MONEY));
    }

    /** Every fiscal year in the company file, newest first. */
    public static List<FiscalYear> fiscalYears(Connection connection) throws SQLException {
        List<FiscalYear> years = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, name, start_date, end_date, is_closed FROM fiscal_year "
                        + "ORDER BY start_date DESC");
             ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                years.add(new FiscalYear(rows.getString(1), rows.getString(2),
                        LocalDate.parse(rows.getString(3)), LocalDate.parse(rows.getString(4)),
                        rows.getInt(5) == 1));
            }
        }
        return years;
    }

    /**
     * The year a report should open on: the one containing {@code today}, or the
     * most recent when none does - a company whose newest year has ended still
     * opens on its latest books rather than on nothing.
     */
    public static Optional<FiscalYear> defaultYear(List<FiscalYear> years, LocalDate today) {
        return years.stream().filter(year -> year.contains(today)).findFirst()
                .or(() -> years.stream().findFirst());
    }
}
