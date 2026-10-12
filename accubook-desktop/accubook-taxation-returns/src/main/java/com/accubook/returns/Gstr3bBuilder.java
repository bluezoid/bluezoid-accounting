package com.accubook.returns;

import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;
import com.accubook.tax.RulePack;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds a GSTR-3B from a period's sales and purchase documents, as they were posted. */
final class Gstr3bBuilder {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private Gstr3bBuilder() {
    }

    /** One taxed or untaxed line with its document's facts, signed: a note's amounts are negative. */
    private record Row(String documentNumber, String category, boolean interState,
                       boolean registered, boolean reverseCharge, String placeOfSupply, Gstr3b.Tax tax) {
    }

    static Gstr3b build(Connection connection, ReturnPeriod period, RulePack rules) throws SQLException {
        String gstin = Gstr1Builder.requireRegularThroughout(connection, period);
        List<Gstr1.Issue> issues = new ArrayList<>();

        Gstr3b.Tax outward = Gstr3b.Tax.NIL;
        BigDecimal nilExempt = ZERO;
        BigDecimal nonGst = ZERO;
        Map<String, BigDecimal[]> unregistered = new TreeMap<>();
        for (Row row : rows(connection, "sales", "CREDIT_NOTE", period, issues)) {
            if (row.category == null) {
                continue;
            }
            switch (row.category) {
                case "TAXABLE" -> {
                    outward = outward.plus(row.tax);
                    if (row.interState && !row.registered) {
                        BigDecimal[] a = unregistered.computeIfAbsent(row.placeOfSupply, k -> new BigDecimal[]{ZERO,
                                ZERO});
                        a[0] = a[0].add(row.tax.taxable());
                        a[1] = a[1].add(row.tax.igst());
                    }
                }
                case "NON_GST" -> nonGst = nonGst.add(row.tax.taxable());
                default -> nilExempt = nilExempt.add(row.tax.taxable());
            }
        }

        Gstr3b.Tax rcmInward = Gstr3b.Tax.NIL;
        Gstr3b.Tax itcOther = Gstr3b.Tax.NIL;
        BigDecimal[] inward = {ZERO, ZERO, ZERO, ZERO};
        for (Row row : rows(connection, "purchase", "DEBIT_NOTE", period, issues)) {
            if (row.category == null) {
                continue;
            }
            switch (row.category) {
                case "TAXABLE" -> {
                    if (row.reverseCharge) {
                        rcmInward = rcmInward.plus(row.tax);
                    } else if (row.registered) {
                        itcOther = itcOther.plus(row.tax);
                    }
                }
                case "NON_GST" -> inward[row.interState ? 2 : 3] = inward[row.interState ? 2 : 3]
                        .add(row.tax.taxable());
                default -> inward[row.interState ? 0 : 1] = inward[row.interState ? 0 : 1].add(row.tax.taxable());
            }
        }

        List<Gstr3b.UnregisteredInterState> interUnreg = new ArrayList<>();
        unregistered.forEach((pos, a) -> interUnreg.add(new Gstr3b.UnregisteredInterState(pos, a[0], a[1])));
        Gstr3b.Tax itcRcm = rcmInward;
        return new Gstr3b(period, gstin, rules.version(), outward, nilExempt, nonGst, rcmInward, interUnreg, itcRcm,
                itcOther, new Gstr3b.Inward(inward[0], inward[1], inward[2], inward[3]),
                setOff(outward, rcmInward, itcRcm.plus(itcOther)), issues);
    }

    private static List<Row> rows(Connection connection, String side, String noteKind, ReturnPeriod period,
                                  List<Gstr1.Issue> issues) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT d.number, d.kind, d.gst_scheme, l.tax_category, d.supply_type, d.party_gstin,
                       d.reverse_charge, d.place_of_supply, l.amount_e4,
                       COALESCE(SUM(CASE WHEN t.component = 'IGST' THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component = 'CGST' THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component IN ('SGST', 'UTGST') THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component = 'CESS' THEN t.amount_e4 END), 0)
                FROM %1$s_document d
                JOIN %1$s_document_line l ON l.document_id = d.id
                LEFT JOIN %1$s_tax_line t ON t.line_id = l.id
                WHERE d.status = 'POSTED' AND d.document_date BETWEEN ? AND ?
                GROUP BY l.id
                ORDER BY d.number, l.sequence
                """.formatted(side))) {
            ps.setString(1, period.from().toString());
            ps.setString(2, period.to().toString());
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    if (r.getString(3) == null) {
                        String number = r.getString(1);
                        if (issues.stream().noneMatch(i -> i.document().equals(number))) {
                            issues.add(new Gstr1.Issue(true, number, "Posted before documents carried GST; "
                                    + "its tax is not recorded line by line."));
                        }
                        continue;
                    }
                    BigDecimal sign = noteKind.equals(r.getString(2)) ? BigDecimal.ONE.negate() : BigDecimal.ONE;
                    Gstr3b.Tax tax = new Gstr3b.Tax(money(r.getLong(9)), money(r.getLong(10)), money(r.getLong(11)),
                            money(r.getLong(12)), money(r.getLong(13)));
                    rows.add(new Row(r.getString(1), r.getString(4), "INTER_STATE".equals(r.getString(5)),
                            r.getString(6) != null, r.getInt(7) == 1, r.getString(8),
                            sign.signum() < 0 ? tax.negate() : tax));
                }
            }
        }
        return rows;
    }

    /** Rule 88A, as a guide: see {@link Gstr3b.SetOff}. */
    static Gstr3b.SetOff setOff(Gstr3b.Tax outward, Gstr3b.Tax reverseCharge, Gstr3b.Tax credit) {
        BigDecimal lI = outward.igst().max(ZERO);
        BigDecimal lC = outward.cgst().max(ZERO);
        BigDecimal lS = outward.sgst().max(ZERO);
        BigDecimal lCess = outward.cess().max(ZERO);
        BigDecimal cI = credit.igst().max(ZERO);
        BigDecimal cC = credit.cgst().max(ZERO);
        BigDecimal cS = credit.sgst().max(ZERO);
        BigDecimal cCess = credit.cess().max(ZERO);

        // IGST credit: against IGST, then where CGST and SGST credit fall short.
        BigDecimal use = cI.min(lI);
        lI = lI.subtract(use);
        cI = cI.subtract(use);
        use = cI.min(lC.subtract(cC).max(ZERO));
        lC = lC.subtract(use);
        cI = cI.subtract(use);
        use = cI.min(lS.subtract(cS).max(ZERO));
        lS = lS.subtract(use);
        cI = cI.subtract(use);
        // CGST credit: against CGST, then IGST. SGST likewise. Never CGST against SGST.
        use = cC.min(lC);
        lC = lC.subtract(use);
        cC = cC.subtract(use);
        use = cC.min(lI);
        lI = lI.subtract(use);
        cC = cC.subtract(use);
        use = cS.min(lS);
        lS = lS.subtract(use);
        cS = cS.subtract(use);
        use = cS.min(lI);
        lI = lI.subtract(use);
        cS = cS.subtract(use);
        use = cCess.min(lCess);
        lCess = lCess.subtract(use);
        cCess = cCess.subtract(use);

        Gstr3b.Tax liability = new Gstr3b.Tax(outward.taxable().add(reverseCharge.taxable()),
                outward.igst().add(reverseCharge.igst()), outward.cgst().add(reverseCharge.cgst()),
                outward.sgst().add(reverseCharge.sgst()), outward.cess().add(reverseCharge.cess()));
        Gstr3b.Tax cash = new Gstr3b.Tax(ZERO, lI.add(reverseCharge.igst()), lC.add(reverseCharge.cgst()),
                lS.add(reverseCharge.sgst()), lCess.add(reverseCharge.cess()));
        return new Gstr3b.SetOff(liability, credit, cash, new Gstr3b.Tax(ZERO, cI, cC, cS, cCess));
    }

    private static BigDecimal money(long stored) {
        return MoneyMapper.fromStored(stored, Scale.MONEY).setScale(2);
    }
}
