package com.accubook.returns;

import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;
import com.accubook.tax.CompositionCategory;
import com.accubook.tax.CompositionRate;
import com.accubook.tax.RulePack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds CMP-08 for a quarter (P9d-4). The rate and base are the category's
 * in the rule pack on each document's date (P9-20); tax is worked out per rate
 * on the quarter's base, each half rounded half-up to the paisa.
 */
final class Cmp08Builder {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private Cmp08Builder() {
    }

    private record Registration(String gstin, CompositionCategory category) {
    }

    static Cmp08 build(Connection connection, ReturnPeriod period, RulePack rules) throws SQLException {
        if (!period.isQuarter()) {
            throw new ReturnException("CMP-08 is a quarterly statement");
        }
        Registration reg = requireCompositionThroughout(connection, period);
        List<Gstr1.Issue> issues = new ArrayList<>();
        BigDecimal turnover = ZERO;
        Map<BigDecimal, BigDecimal> baseByRate = new TreeMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT d.number, d.kind, d.document_date, d.gst_scheme, l.tax_category, l.amount_e4
                FROM sales_document d JOIN sales_document_line l ON l.document_id = d.id
                WHERE d.status = 'POSTED' AND d.document_date BETWEEN ? AND ?
                ORDER BY d.number, l.sequence
                """)) {
            ps.setString(1, period.from().toString());
            ps.setString(2, period.to().toString());
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    BigDecimal amount = money(r.getLong(6));
                    if ("CREDIT_NOTE".equals(r.getString(2))) {
                        amount = amount.negate();
                    }
                    turnover = turnover.add(amount);
                    String category = r.getString(5);
                    if (r.getString(4) == null || category == null) {
                        String number = r.getString(1);
                        if (issues.stream().noneMatch(i -> i.document().equals(number))) {
                            issues.add(new Gstr1.Issue(true, number, "Its lines do not say what was supplied - "
                                    + "taxable or exempt - so its composition tax cannot be worked out."));
                        }
                        continue;
                    }
                    CompositionRate rate = rules.compositionRate(reg.category, LocalDate.parse(r.getString(3)));
                    if (rate.base() == CompositionRate.Base.TURNOVER || "TAXABLE".equals(category)) {
                        baseByRate.merge(rate.rate(), amount, BigDecimal::add);
                    }
                }
            }
        }
        BigDecimal base = ZERO;
        BigDecimal cgst = ZERO;
        BigDecimal sgst = ZERO;
        for (Map.Entry<BigDecimal, BigDecimal> e : baseByRate.entrySet()) {
            BigDecimal half = e.getValue().multiply(e.getKey())
                    .divide(BigDecimal.valueOf(200), 2, RoundingMode.HALF_UP);
            base = base.add(e.getValue());
            cgst = cgst.add(half);
            sgst = sgst.add(half);
        }
        return new Cmp08(period, reg.gstin, reg.category, rules.version(), turnover, base, cgst, sgst,
                reverseCharge(connection, period), issues);
    }

    private static Gstr3b.Tax reverseCharge(Connection connection, ReturnPeriod period) throws SQLException {
        String where = " WHERE d.status = 'POSTED' AND d.reverse_charge = 1 AND d.document_date BETWEEN ? AND ?";
        String sign = "CASE d.kind WHEN 'DEBIT_NOTE' THEN -1 ELSE 1 END";
        BigDecimal taxable;
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(l.amount_e4 * " + sign
                + "), 0) FROM purchase_document d JOIN purchase_document_line l ON l.document_id = d.id" + where)) {
            ps.setString(1, period.from().toString());
            ps.setString(2, period.to().toString());
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                taxable = money(r.getLong(1));
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT "
                + "COALESCE(SUM(CASE WHEN t.component = 'IGST' THEN t.amount_e4 * " + sign + " END), 0), "
                + "COALESCE(SUM(CASE WHEN t.component = 'CGST' THEN t.amount_e4 * " + sign + " END), 0), "
                + "COALESCE(SUM(CASE WHEN t.component IN ('SGST', 'UTGST') THEN t.amount_e4 * " + sign + " END), 0), "
                + "COALESCE(SUM(CASE WHEN t.component = 'CESS' THEN t.amount_e4 * " + sign + " END), 0) "
                + "FROM purchase_document d JOIN purchase_document_line l ON l.document_id = d.id "
                + "JOIN purchase_tax_line t ON t.line_id = l.id" + where)) {
            ps.setString(1, period.from().toString());
            ps.setString(2, period.to().toString());
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return new Gstr3b.Tax(taxable, money(r.getLong(1)), money(r.getLong(2)), money(r.getLong(3)),
                        money(r.getLong(4)));
            }
        }
    }

    /** CMP-08 is a composition dealer's: one composition registration, one category, all quarter. */
    private static Registration requireCompositionThroughout(Connection connection, ReturnPeriod period)
            throws SQLException {
        List<Object[]> spanning = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT scheme, gstin, composition_category, "
                + "effective_from FROM gst_registration WHERE effective_from <= ? AND (effective_from > ? OR "
                + "effective_from = (SELECT MAX(effective_from) FROM gst_registration WHERE effective_from <= ?)) "
                + "ORDER BY effective_from")) {
            ps.setString(1, period.to().toString());
            ps.setString(2, period.from().toString());
            ps.setString(3, period.from().toString());
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    spanning.add(new Object[]{rows.getString(1), rows.getString(2), rows.getString(3),
                            LocalDate.parse(rows.getString(4))});
                }
            }
        }
        if (spanning.isEmpty() || ((LocalDate) spanning.get(0)[3]).isAfter(period.from())) {
            throw new ReturnException("The company has no GST registration from " + period.from());
        }
        for (Object[] r : spanning) {
            if (!"COMPOSITION".equals(r[0])) {
                throw new ReturnException("CMP-08 is a composition dealer's statement; the company is "
                        + r[0].toString().toLowerCase(java.util.Locale.ROOT) + " from " + r[3]);
            }
            if (r[2] == null) {
                throw new ReturnException("The composition registration from " + r[3]
                        + " has no category; record one to work out the composition tax");
            }
            if (!r[1].equals(spanning.get(0)[1]) || !r[2].equals(spanning.get(0)[2])) {
                throw new ReturnException("The GSTIN or composition category changes within the quarter");
            }
        }
        return new Registration((String) spanning.get(0)[1],
                CompositionCategory.valueOf((String) spanning.get(0)[2]));
    }

    private static BigDecimal money(long stored) {
        return MoneyMapper.fromStored(stored, Scale.MONEY).setScale(2);
    }
}
