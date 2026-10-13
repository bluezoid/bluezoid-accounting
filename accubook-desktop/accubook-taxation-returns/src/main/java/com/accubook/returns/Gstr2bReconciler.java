package com.accubook.returns;

import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds a {@link Gstr2bReconciliation} from the current import for a month and the books. */
final class Gstr2bReconciler {

    private static final BigDecimal TOLERANCE = BigDecimal.ONE;

    private Gstr2bReconciler() {
    }

    private record Side(String type, String ctin, String name, String number, String key, LocalDate date,
                        String documentId, Gstr2bReconciliation.Figures figures, boolean reverseCharge,
                        boolean itcAvailable) {
    }

    /** The current import's id for {@code period}, if the month's 2B has been imported. */
    static Optional<String> currentImport(Connection connection, ReturnPeriod period) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM gstr2b_import WHERE period_from = ? AND status = 'CURRENT'")) {
            ps.setString(1, period.from().toString());
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }

    static Optional<Gstr2bReconciliation> build(Connection connection, ReturnPeriod period) throws SQLException {
        Optional<String> importId = currentImport(connection, period);
        if (importId.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Side> twoB = new LinkedHashMap<>();
        for (Side s : imported(connection, importId.get())) {
            twoB.put(s.type + "|" + s.ctin + "|" + s.key, s);
        }
        List<Side> books = books(connection, period);
        List<Gstr2bReconciliation.Row> rows = new ArrayList<>();
        List<Side> onlyBooks = new ArrayList<>();
        for (Side b : books) {
            Side t = b.key == null ? null : twoB.remove(b.type + "|" + b.ctin + "|" + b.key);
            if (t == null) {
                onlyBooks.add(b);
                continue;
            }
            List<String> differences = differences(b, t);
            rows.add(new Gstr2bReconciliation.Row(differences.isEmpty() ? Gstr2bReconciliation.Status.MATCHED
                    : Gstr2bReconciliation.Status.MISMATCHED, b.type, b.ctin, t.name == null ? b.name : t.name,
                    t.number, t.date, b.documentId, b.figures, t.figures, t.reverseCharge, t.itcAvailable,
                    b.number.equals(t.number), differences, null));
        }
        List<Side> onlyTwoB = new ArrayList<>(twoB.values());
        for (Side t : onlyTwoB) {
            rows.add(new Gstr2bReconciliation.Row(Gstr2bReconciliation.Status.ONLY_IN_2B, t.type, t.ctin, t.name,
                    t.number, t.date, null, Gstr2bReconciliation.Figures.NONE, t.figures, t.reverseCharge,
                    t.itcAvailable, false, List.of(), probable(t, onlyBooks)));
        }
        for (Side b : onlyBooks) {
            rows.add(new Gstr2bReconciliation.Row(Gstr2bReconciliation.Status.ONLY_IN_BOOKS, b.type, b.ctin, b.name,
                    b.number, b.date, b.documentId, b.figures, Gstr2bReconciliation.Figures.NONE, b.reverseCharge,
                    true, false, b.key == null ? List.of("No supplier document number recorded on the bill")
                    : List.of(), probable(b, onlyTwoB)));
        }
        return Optional.of(new Gstr2bReconciliation(period, importId.get(), rows));
    }

    /** P9-17: ₹1 per figure; and the date. */
    private static List<String> differences(Side books, Side twoB) {
        List<String> out = new ArrayList<>();
        compare(out, "value", books.figures.value(), twoB.figures.value());
        compare(out, "taxable value", books.figures.taxable(), twoB.figures.taxable());
        compare(out, "IGST", books.figures.igst(), twoB.figures.igst());
        compare(out, "CGST", books.figures.cgst(), twoB.figures.cgst());
        compare(out, "SGST", books.figures.sgst(), twoB.figures.sgst());
        compare(out, "cess", books.figures.cess(), twoB.figures.cess());
        if (!books.date.equals(twoB.date)) {
            out.add("date: books " + books.date + ", 2B " + twoB.date);
        }
        return out;
    }

    private static void compare(List<String> out, String what, BigDecimal books, BigDecimal twoB) {
        if (books.subtract(twoB).abs().compareTo(TOLERANCE) > 0) {
            out.add(what + ": books " + books.toPlainString() + ", 2B " + twoB.toPlainString());
        }
    }

    private static String probable(Side side, List<Side> others) {
        List<Side> same = others.stream().filter(o -> o.ctin.equals(side.ctin) && o.type.equals(side.type)
                && o.figures.value().subtract(side.figures.value()).abs().compareTo(TOLERANCE) <= 0).toList();
        return same.size() == 1 ? same.get(0).number : null;
    }

    private static List<Side> imported(Connection connection, String importId) throws SQLException {
        List<Side> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT doc_type, ctin, trade_name, number, "
                + "number_key, doc_date, reverse_charge, itc_available, value_e4, taxable_e4, igst_e4, cgst_e4, "
                + "sgst_e4, cess_e4 FROM gstr2b_document WHERE import_id = ? AND doc_type IN ('INV', 'CN') "
                + "ORDER BY ctin, number_key")) {
            ps.setString(1, importId);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    result.add(new Side(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getString(5), LocalDate.parse(r.getString(6)), null, new Gstr2bReconciliation.Figures(
                            money(r.getLong(9)), money(r.getLong(10)), money(r.getLong(11)), money(r.getLong(12)),
                            money(r.getLong(13)), money(r.getLong(14))), r.getInt(7) == 1, r.getInt(8) == 1));
                }
            }
        }
        return result;
    }

    /**
     * Posted purchase bills (INV) and debit notes (the supplier's CN) from vendors
     * with a GSTIN, dated up to the month's end, not matched in an earlier
     * month's current 2B.
     */
    private static List<Side> books(Connection connection, ReturnPeriod period) throws SQLException {
        List<Side> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT d.id, d.kind, d.party_gstin, p.name, d.supplier_document_no, d.document_date,
                       d.total_e4, d.taxable_e4, d.reverse_charge,
                       COALESCE(SUM(CASE WHEN t.component = 'IGST' THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component = 'CGST' THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component IN ('SGST', 'UTGST') THEN t.amount_e4 END), 0),
                       COALESCE(SUM(CASE WHEN t.component = 'CESS' THEN t.amount_e4 END), 0)
                FROM purchase_document d
                JOIN party p ON p.id = d.party_id
                LEFT JOIN purchase_document_line l ON l.document_id = d.id
                LEFT JOIN purchase_tax_line t ON t.line_id = l.id
                WHERE d.status = 'POSTED' AND d.party_gstin IS NOT NULL AND d.document_date <= ?
                GROUP BY d.id
                ORDER BY d.document_date, d.number
                """)) {
            ps.setString(1, period.to().toString());
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String number = r.getString(5);
                    String type = "DEBIT_NOTE".equals(r.getString(2)) ? "CN" : "INV";
                    result.add(new Side(type, r.getString(3), r.getString(4), number == null ? "" : number,
                            number == null ? null : Gstr2bFile.key(number), LocalDate.parse(r.getString(6)),
                            r.getString(1), new Gstr2bReconciliation.Figures(money(r.getLong(7)), money(r.getLong(8)),
                            money(r.getLong(10)), money(r.getLong(11)), money(r.getLong(12)), money(r.getLong(13))),
                            r.getInt(9) == 1, true));
                }
            }
        }
        List<Side> unmatchedEarlier = new ArrayList<>();
        for (Side s : result) {
            if (s.key == null || !matchedEarlier(connection, period, s)) {
                unmatchedEarlier.add(s);
            }
        }
        return unmatchedEarlier;
    }

    private static boolean matchedEarlier(Connection connection, ReturnPeriod period, Side s) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM gstr2b_document g "
                + "JOIN gstr2b_import i ON i.id = g.import_id WHERE i.status = 'CURRENT' AND i.period_from < ? "
                + "AND g.doc_type = ? AND g.ctin = ? AND g.number_key = ?)")) {
            ps.setString(1, period.from().toString());
            ps.setString(2, s.type);
            ps.setString(3, s.ctin);
            ps.setString(4, s.key);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return r.getInt(1) == 1;
            }
        }
    }

    private static BigDecimal money(long stored) {
        return MoneyMapper.fromStored(stored, Scale.MONEY).setScale(2);
    }
}
