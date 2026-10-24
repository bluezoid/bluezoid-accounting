package com.accubook.accounting.report;

import com.accubook.module.ModuleTransactions;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Profit and loss, balance sheet and cash flow, from posted vouchers only
 * (Phase 11b). One query shape for all three, so they cannot disagree.
 */
public final class FinancialStatements {

    /** Year-end closing vouchers and their reversals: not trading, so left out of profit and loss. */
    private static final String NOT_YEAR_END = " AND v.id NOT IN (SELECT voucher_id FROM fiscal_year_event "
            + "WHERE action IN ('CLOSE', 'REOPEN') AND voucher_id IS NOT NULL)";

    private final ModuleTransactions transactions;

    public FinancialStatements(ModuleTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public ProfitAndLoss profitAndLoss(LocalDate from, LocalDate to) {
        return read(c -> {
            Chart chart = Chart.load(c);
            Map<String, BigDecimal> net = sums(c, from, to, NOT_YEAR_END);
            List<StatementLine> trading = new ArrayList<>();
            List<StatementLine> direct = new ArrayList<>();
            List<StatementLine> other = new ArrayList<>();
            List<StatementLine> indirect = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : net.entrySet()) {
                Chart.Group g = chart.groupOf(e.getKey());
                if ("INCOME".equals(g.nature())) {
                    (chart.under(g.id(), "SALES_ACCOUNTS") ? trading : other)
                            .add(line(chart, e.getKey(), e.getValue().negate()));
                } else if ("EXPENSES".equals(g.nature())) {
                    boolean grossProfit = chart.lineage(g.id()).stream().anyMatch(Chart.Group::grossProfit);
                    (grossProfit ? direct : indirect).add(line(chart, e.getKey(), e.getValue()));
                }
            }
            BigDecimal gross = sum(trading).subtract(sum(direct));
            return new ProfitAndLoss(from, to, sorted(trading), sorted(direct), gross, sorted(other),
                    sorted(indirect), gross.add(sum(other)).subtract(sum(indirect)));
        });
    }

    public BalanceSheet balanceSheet(LocalDate asOf) {
        return read(c -> {
            Chart chart = Chart.load(c);
            Map<String, BigDecimal> net = sums(c, LocalDate.of(1, 1, 1), asOf, "");
            List<StatementLine> assets = new ArrayList<>();
            List<StatementLine> liabilities = new ArrayList<>();
            List<StatementLine> equity = new ArrayList<>();
            BigDecimal profitAndLoss = BigDecimal.ZERO;
            for (Map.Entry<String, BigDecimal> e : net.entrySet()) {
                switch (chart.groupOf(e.getKey()).nature()) {
                    case "ASSETS" -> assets.add(line(chart, e.getKey(), e.getValue()));
                    case "LIABILITIES" -> liabilities.add(line(chart, e.getKey(), e.getValue().negate()));
                    case "EQUITY" -> equity.add(line(chart, e.getKey(), e.getValue().negate()));
                    default -> profitAndLoss = profitAndLoss.add(e.getValue());
                }
            }
            BigDecimal unclosed = profitAndLoss.negate();
            return new BalanceSheet(asOf, sorted(assets), sorted(liabilities), sorted(equity), unclosed, sum(assets),
                    sum(liabilities).add(sum(equity)).add(unclosed));
        });
    }

    /**
     * P11-2: the direct method. Each voucher that moves cash or bank contributes,
     * for every other line in it, that line's opposite: a customer credited
     * 1,000 against the bank is 1,000 in. Lines against another cash or bank
     * ledger - contra transfers - are not flows.
     */
    public CashFlow cashFlow(LocalDate from, LocalDate to) {
        return read(c -> {
            Chart chart = Chart.load(c);
            Map<String, String> overrides = overrides(c);
            List<String> cash = chart.ledgers.values().stream()
                    .filter(l -> chart.under(l.groupId(), "CASH_AND_BANK")).map(Chart.Ledger::id).toList();
            Map<String, Map<String, BigDecimal>> byActivity = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT e.ledger_id, e.debit_amount_e4 - e.credit_amount_e4
                    FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id
                    WHERE v.is_posted = 1 AND v.voucher_date BETWEEN ? AND ?
                      AND v.id NOT IN (SELECT voucher_id FROM fiscal_year_event WHERE action = 'OPENING')
                      AND EXISTS (SELECT 1 FROM voucher_entry x JOIN ledger l ON l.id = x.ledger_id
                                  WHERE x.voucher_id = v.id AND l.id IN (%s))
                    """.formatted(placeholders(cash.size())))) {
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
                for (int i = 0; i < cash.size(); i++) {
                    ps.setString(3 + i, cash.get(i));
                }
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        String ledgerId = r.getString(1);
                        if (cash.contains(ledgerId)) {
                            continue;
                        }
                        Chart.Group g = chart.groupOf(ledgerId);
                        BigDecimal flow = money(r.getLong(2)).negate();
                        byActivity.computeIfAbsent(activity(chart, overrides, g.id()), k -> new TreeMap<>())
                                .merge(g.name(), flow, BigDecimal::add);
                    }
                }
            }
            // The opening voucher states balances; it is not a flow, so its cash is opening cash.
            BigDecimal opening = cashBalance(c, cash, from.minusDays(1)).add(openingVoucherCash(c, cash, from, to));
            BigDecimal closing = cashBalance(c, cash, to);
            return new CashFlow(from, to, opening, flows(byActivity.get("OPERATING")),
                    flows(byActivity.get("INVESTING")), flows(byActivity.get("FINANCING")), closing);
        });
    }

    /** P11-2: classify a group's cash flows; its subgroups follow unless they have their own. */
    public void classify(String groupId, String activity) {
        if (!List.of("OPERATING", "INVESTING", "FINANCING").contains(activity)) {
            throw new IllegalArgumentException("A cash flow activity is OPERATING, INVESTING or FINANCING");
        }
        try {
            transactions.inTransaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO cash_flow_activity (group_id, activity, "
                        + "updated_at) VALUES (?, ?, ?) ON CONFLICT (group_id) DO UPDATE SET "
                        + "activity = excluded.activity, updated_at = excluded.updated_at")) {
                    ps.setString(1, groupId);
                    ps.setString(2, activity);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new IllegalStateException("Could not classify the group: " + e.getMessage(), e);
        }
    }

    /** The nearest classified ancestor's activity; else fixed assets invest, loans and capital finance. */
    static String activity(Chart chart, Map<String, String> overrides, String groupId) {
        for (Chart.Group g : chart.lineage(groupId)) {
            if (overrides.containsKey(g.id())) {
                return overrides.get(g.id());
            }
        }
        if (chart.under(groupId, "FIXED_ASSETS")) {
            return "INVESTING";
        }
        if (chart.under(groupId, "LOANS_LIABILITY") || chart.under(groupId, "CAPITAL_ACCOUNT")) {
            return "FINANCING";
        }
        return "OPERATING";
    }

    // ================================================================ helpers

    private static Map<String, BigDecimal> sums(Connection c, LocalDate from, LocalDate to, String filter)
            throws SQLException {
        Map<String, BigDecimal> result = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT e.ledger_id, SUM(e.debit_amount_e4 - "
                + "e.credit_amount_e4) FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id WHERE "
                + "v.is_posted = 1 AND v.voucher_date BETWEEN ? AND ?" + filter + " GROUP BY e.ledger_id")) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    if (r.getLong(2) != 0) {
                        result.put(r.getString(1), money(r.getLong(2)));
                    }
                }
            }
        }
        return result;
    }

    private static BigDecimal cashBalance(Connection c, List<String> cash, LocalDate asOf) throws SQLException {
        if (cash.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(e.debit_amount_e4 - e.credit_amount_e4), "
                + "0) FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id WHERE v.is_posted = 1 "
                + "AND v.voucher_date <= ? AND e.ledger_id IN (" + placeholders(cash.size()) + ")")) {
            ps.setString(1, asOf.toString());
            for (int i = 0; i < cash.size(); i++) {
                ps.setString(2 + i, cash.get(i));
            }
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return money(r.getLong(1));
            }
        }
    }

    private static BigDecimal openingVoucherCash(Connection c, List<String> cash, LocalDate from, LocalDate to)
            throws SQLException {
        if (cash.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(e.debit_amount_e4 - e.credit_amount_e4), "
                + "0) FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id WHERE v.is_posted = 1 "
                + "AND v.voucher_date BETWEEN ? AND ? AND v.id IN (SELECT voucher_id FROM fiscal_year_event "
                + "WHERE action = 'OPENING') AND e.ledger_id IN (" + placeholders(cash.size()) + ")")) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            for (int i = 0; i < cash.size(); i++) {
                ps.setString(3 + i, cash.get(i));
            }
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return money(r.getLong(1));
            }
        }
    }

    private static Map<String, String> overrides(Connection c) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT group_id, activity FROM cash_flow_activity");
             ResultSet r = ps.executeQuery()) {
            while (r.next()) {
                result.put(r.getString(1), r.getString(2));
            }
        }
        return result;
    }

    private static List<CashFlow.Flow> flows(Map<String, BigDecimal> byGroup) {
        List<CashFlow.Flow> result = new ArrayList<>();
        if (byGroup != null) {
            byGroup.forEach((name, amount) -> {
                if (amount.signum() != 0) {
                    result.add(new CashFlow.Flow(name, amount));
                }
            });
        }
        return result;
    }

    private static StatementLine line(Chart chart, String ledgerId, BigDecimal amount) {
        return new StatementLine(chart.heading(chart.ledgers.get(ledgerId).groupId()).name(), ledgerId,
                chart.ledgers.get(ledgerId).name(), amount);
    }

    private static List<StatementLine> sorted(List<StatementLine> lines) {
        return lines.stream().sorted(Comparator.comparing(StatementLine::heading)
                .thenComparing(StatementLine::ledgerName)).toList();
    }

    private static BigDecimal sum(List<StatementLine> lines) {
        return lines.stream().map(StatementLine::amount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private static String placeholders(int n) {
        return n == 0 ? "NULL" : String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    private static BigDecimal money(long stored) {
        return MoneyMapper.fromStored(stored, Scale.MONEY).setScale(2);
    }

    private <T> T read(ModuleTransactions.Work<T> work) {
        try {
            return transactions.read(work);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the books: " + e.getMessage(), e);
        }
    }
}
