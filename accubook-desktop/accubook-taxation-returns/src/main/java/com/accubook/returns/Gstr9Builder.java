package com.accubook.returns;

import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;
import com.accubook.tax.RulePack;

import java.math.BigDecimal;
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
 * Builds GSTR-9 from the twelve months' GSTR-1 and GSTR-3B as the books stand,
 * the filed GSTR-3B snapshots, and the imported GSTR-2Bs (P9-22). A month whose
 * returns were not filed, or that differ from the books, is reported - as a
 * warning, for the user to explain in the return.
 */
final class Gstr9Builder {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private Gstr9Builder() {
    }

    static Gstr9 build(Connection connection, ReturnPeriod year, RulePack rules) throws SQLException {
        LocalDate yearStart = year.from();
        LocalDate yearEnd = year.to();
        if (!yearEnd.equals(yearStart.plusYears(1).minusDays(1))) {
            throw new ReturnException("GSTR-9 is for a financial year, April to March");
        }
        List<Gstr1.Issue> issues = new ArrayList<>();
        Gstr3b.Tax b2c = Gstr3b.Tax.NIL;
        Gstr3b.Tax b2b = Gstr3b.Tax.NIL;
        Gstr3b.Tax notes = Gstr3b.Tax.NIL;
        BigDecimal exempt = ZERO;
        BigDecimal nil = ZERO;
        BigDecimal nonGst = ZERO;
        Gstr3b.Tax itcInward = Gstr3b.Tax.NIL;
        Gstr3b.Tax itcRcm = Gstr3b.Tax.NIL;
        Gstr3b.Tax payable = Gstr3b.Tax.NIL;
        Map<String, BigDecimal[]> hsn = new TreeMap<>();
        String gstin = null;

        for (int m = 0; m < 12; m++) {
            ReturnPeriod month = ReturnPeriod.month(yearStart.plusMonths(m).getYear(),
                    yearStart.plusMonths(m).getMonthValue());
            Gstr1 g1 = Gstr1Builder.build(connection, month, rules);
            Gstr3b g3 = Gstr3bBuilder.build(connection, month, rules);
            gstin = g1.gstin();
            for (Gstr1.B2b b : g1.b2b()) {
                b2b = b2b.add(items(b.items()));
            }
            for (Gstr1.B2cl b : g1.b2cl()) {
                b2c = b2c.add(items(b.items()));
            }
            for (Gstr1.B2cs b : g1.b2cs()) {
                b2c = b2c.add(new Gstr3b.Tax(b.taxable(), b.igst(), b.cgst(), b.sgst(), b.cess()));
            }
            for (Gstr1.Cdnr n : g1.cdnr()) {
                notes = notes.add(items(n.items()));
            }
            for (Gstr1.Cdnur n : g1.cdnur()) {
                notes = notes.add(items(n.items()));
            }
            for (Gstr1.Nil n : g1.nil()) {
                nil = nil.add(n.nilRated());
                exempt = exempt.add(n.exempt());
                nonGst = nonGst.add(n.nonGst());
            }
            for (Gstr1.Hsn h : g1.hsn()) {
                String key = h.hsn() + "|" + h.uqc() + "|" + h.rate().toPlainString();
                BigDecimal[] a = hsn.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, ZERO, ZERO, ZERO,
                        ZERO, ZERO, ZERO});
                BigDecimal[] add = {h.quantity(), h.value(), h.taxable(), h.igst(), h.cgst(), h.sgst(), h.cess()};
                for (int i = 0; i < a.length; i++) {
                    a[i] = a[i].add(add[i]);
                }
            }
            itcInward = itcInward.add(g3.itcOther());
            itcRcm = itcRcm.add(g3.itcReverseCharge());
            payable = payable.add(g3.setOff().liability());
            for (Gstr1.Issue issue : g1.issues()) {
                if (issue.blocking()) {
                    issues.add(new Gstr1.Issue(false, issue.document(), month.fp() + ": " + issue.message()));
                }
            }
        }

        Gstr3b.Tax itcFiled = Gstr3b.Tax.NIL;
        Gstr3b.Tax outwardFiled = Gstr3b.Tax.NIL;
        int filed3b = 0;
        for (String json : snapshots(connection, "GSTR3B", yearStart, yearEnd)) {
            filed3b++;
            Map<String, Object> root = map(JsonReader.read(json));
            itcFiled = itcFiled.add(tax(map(map(root.get("itc_elg")).get("itc_net")), false));
            Map<String, Object> sup = map(root.get("sup_details"));
            outwardFiled = outwardFiled.add(tax(map(sup.get("osup_det")), true));
        }
        int filed1 = snapshots(connection, "GSTR1", yearStart, yearEnd).size();
        if (filed1 < 12 || filed3b < 12) {
            issues.add(new Gstr1.Issue(false, "", "Filed for the year: " + filed1 + " GSTR-1 and " + filed3b
                    + " GSTR-3B of 12 months; table 6A counts only the filed GSTR-3Bs"));
        }
        Gstr3b.Tax outwardBooks = b2b.add(b2c).add(notes.negate());
        if (filed3b == 12 && !same(outwardBooks, outwardFiled)) {
            issues.add(new Gstr1.Issue(false, "", "Outward supplies in the books differ from the GSTR-3Bs filed"));
        }

        List<Gstr1.Hsn> hsnRows = new ArrayList<>();
        hsn.forEach((key, a) -> {
            String[] k = key.split("\\|");
            hsnRows.add(new Gstr1.Hsn(false, k[0], k[1], new BigDecimal(k[2]), a[0].stripTrailingZeros(), a[1], a[2],
                    a[3], a[4], a[5], a[6]));
        });
        return new Gstr9(yearStart, yearEnd, gstin, b2c, b2b, notes, exempt, nil, nonGst, itcFiled, itcInward, itcRcm,
                itcAs2b(connection, yearStart, yearEnd), payable, outwardFiled, hsnRows, filed1, filed3b, issues);
    }

    private static Gstr3b.Tax items(List<Gstr1.RateItem> items) {
        Gstr3b.Tax t = Gstr3b.Tax.NIL;
        for (Gstr1.RateItem i : items) {
            t = t.add(new Gstr3b.Tax(i.taxable(), i.igst(), i.cgst(), i.sgst(), i.cess()));
        }
        return t;
    }

    private static boolean same(Gstr3b.Tax a, Gstr3b.Tax b) {
        return a.taxable().compareTo(b.taxable()) == 0 && a.igst().compareTo(b.igst()) == 0
                && a.cgst().compareTo(b.cgst()) == 0 && a.sgst().compareTo(b.sgst()) == 0
                && a.cess().compareTo(b.cess()) == 0;
    }

    private static List<String> snapshots(Connection connection, String type, LocalDate from, LocalDate to)
            throws SQLException {
        List<String> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT snapshot FROM gst_return WHERE return_type = ? "
                + "AND status = 'FILED' AND period_from BETWEEN ? AND ? ORDER BY period_from")) {
            ps.setString(1, type);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getString(1));
                }
            }
        }
        return result;
    }

    /** Table 8A: credit on invoices in the year's current GSTR-2Bs, less suppliers' credit notes. */
    private static Gstr3b.Tax itcAs2b(Connection connection, LocalDate from, LocalDate to) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COALESCE(SUM(CASE g.doc_type WHEN 'CN' THEN -g.taxable_e4 ELSE g.taxable_e4 END), 0),
                       COALESCE(SUM(CASE g.doc_type WHEN 'CN' THEN -g.igst_e4 ELSE g.igst_e4 END), 0),
                       COALESCE(SUM(CASE g.doc_type WHEN 'CN' THEN -g.cgst_e4 ELSE g.cgst_e4 END), 0),
                       COALESCE(SUM(CASE g.doc_type WHEN 'CN' THEN -g.sgst_e4 ELSE g.sgst_e4 END), 0),
                       COALESCE(SUM(CASE g.doc_type WHEN 'CN' THEN -g.cess_e4 ELSE g.cess_e4 END), 0)
                FROM gstr2b_document g JOIN gstr2b_import i ON i.id = g.import_id
                WHERE i.status = 'CURRENT' AND i.period_from BETWEEN ? AND ? AND g.itc_available = 1
                  AND g.reverse_charge = 0 AND g.doc_type IN ('INV', 'CN')
                """)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return new Gstr3b.Tax(money(r.getLong(1)), money(r.getLong(2)), money(r.getLong(3)),
                        money(r.getLong(4)), money(r.getLong(5)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static Gstr3b.Tax tax(Map<String, Object> m, boolean withTaxable) {
        return new Gstr3b.Tax(withTaxable ? (BigDecimal) m.get("txval") : ZERO, (BigDecimal) m.get("iamt"),
                (BigDecimal) m.get("camt"), (BigDecimal) m.get("samt"), (BigDecimal) m.get("csamt"));
    }

    private static BigDecimal money(long stored) {
        return MoneyMapper.fromStored(stored, Scale.MONEY).setScale(2);
    }
}
