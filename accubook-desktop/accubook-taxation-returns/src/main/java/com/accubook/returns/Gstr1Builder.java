package com.accubook.returns;

import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.RulePack;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a GSTR-1 from the sales documents of a period, as they were posted.
 *
 * <ul>
 *   <li>An invoice to a customer with a GSTIN is <b>B2B</b>. To an unregistered
 *       buyer it is <b>B2CL</b> when inter-state and above the rule pack's limit
 *       on its date (above ₹1 lakh from 1 Aug 2024 - the reference keeps ₹2.5 lakh
 *       and {@code >=}, R-19), else <b>B2CS</b>.</li>
 *   <li>A credit note follows its invoice: CDNR, CDNUR (a B2CL invoice's), or
 *       netted into B2CS.</li>
 *   <li>Nil-rated, exempt and non-GST lines go to table 8, each in its own
 *       column (the reference sums them into one).</li>
 *   <li>Cancelled documents are counted in table 13, not dropped (R-20).</li>
 *   <li>A taxable line without HSN/SAC blocks filing; a unit without a GST code
 *       reports as {@code OTH}, with a warning (P9-16, R-21).</li>
 * </ul>
 */
final class Gstr1Builder {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private Gstr1Builder() {
    }

    private record Doc(String id, String kind, String number, String status, LocalDate date, String gstin,
                       String partyName, String placeOfSupply, boolean interState, BigDecimal total,
                       String scheme, String originalId) {
    }

    private record Line(String category, BigDecimal rate, BigDecimal taxable, BigDecimal quantity, String hsn,
                        String uqc, String description, BigDecimal igst, BigDecimal cgst, BigDecimal sgst,
                        BigDecimal cess) {
        BigDecimal tax() {
            return igst.add(cgst).add(sgst).add(cess);
        }
    }

    static Gstr1 build(Connection connection, ReturnPeriod period, RulePack rules) throws SQLException {
        String gstin = requireRegularThroughout(connection, period);
        List<Gstr1.Issue> issues = new ArrayList<>();
        List<Gstr1.B2b> b2b = new ArrayList<>();
        List<Gstr1.B2cl> b2cl = new ArrayList<>();
        Map<String, BigDecimal[]> b2cs = new TreeMap<>();
        List<Gstr1.Cdnr> cdnr = new ArrayList<>();
        List<Gstr1.Cdnur> cdnur = new ArrayList<>();
        Map<String, BigDecimal[]> nil = new TreeMap<>();
        Map<String, BigDecimal[]> hsn = new TreeMap<>();
        Map<String, List<Doc>> issued = new LinkedHashMap<>();
        issued.put("Invoices for outward supply", new ArrayList<>());
        issued.put("Credit Note", new ArrayList<>());

        for (Doc doc : documents(connection, period)) {
            issued.get("SALES_INVOICE".equals(doc.kind) ? "Invoices for outward supply" : "Credit Note").add(doc);
            if (!"POSTED".equals(doc.status)) {
                continue;
            }
            if (doc.scheme == null) {
                issues.add(new Gstr1.Issue(true, doc.number, "Posted before documents carried GST; its tax is not "
                        + "recorded line by line. Report it by hand or correct it with a note."));
                continue;
            }
            boolean note = "CREDIT_NOTE".equals(doc.kind);
            BigDecimal sign = note ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            boolean registered = doc.gstin != null;
            List<Line> lines = lines(connection, doc.id);
            List<Gstr1.RateItem> items = rateItems(lines);

            for (Line line : lines) {
                if (line.category == null) {
                    continue;
                }
                if (!"TAXABLE".equals(line.category)) {
                    BigDecimal[] bucket = nil.computeIfAbsent(nilKey(doc.interState, registered),
                            k -> new BigDecimal[]{ZERO, ZERO, ZERO});
                    int column = switch (line.category) {
                        case "NIL_RATED" -> 0;
                        case "EXEMPT" -> 1;
                        default -> 2;
                    };
                    bucket[column] = bucket[column].add(line.taxable.multiply(sign));
                }
                addHsn(hsn, issues, doc, line, registered, sign);
            }
            if (items.isEmpty()) {
                continue;
            }
            if (!note) {
                if (registered) {
                    b2b.add(new Gstr1.B2b(doc.gstin, doc.partyName, doc.number, doc.date, doc.total,
                            doc.placeOfSupply, false, items));
                } else if (doc.interState && doc.total.compareTo(rules.threshold("B2CL_INVOICE", doc.date)) > 0) {
                    b2cl.add(new Gstr1.B2cl(doc.placeOfSupply, doc.number, doc.date, doc.total, items));
                } else {
                    addB2cs(b2cs, doc, items, BigDecimal.ONE);
                }
                continue;
            }
            Doc original = original(connection, doc.originalId);
            if (registered) {
                cdnr.add(new Gstr1.Cdnr(doc.gstin, doc.partyName, doc.number, doc.date, doc.total,
                        doc.placeOfSupply, items));
            } else if (original.interState
                    && original.total.compareTo(rules.threshold("B2CL_INVOICE", original.date)) > 0) {
                cdnur.add(new Gstr1.Cdnur(doc.number, doc.date, doc.total, doc.placeOfSupply, items));
            } else {
                addB2cs(b2cs, doc, items, BigDecimal.ONE.negate());
            }
        }

        return new Gstr1(period, gstin, rules.version(), b2b, b2cl, b2csRows(b2cs), cdnr, cdnur, nilRows(nil),
                hsnRows(hsn), docRanges(issued), issues);
    }

    /** GSTR-1 is a regular taxpayer's return: the whole period must be under one regular registration. */
    static String requireRegularThroughout(Connection connection, ReturnPeriod period) throws SQLException {
        List<GstRegistration> spanning = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT scheme, gstin, effective_from FROM "
                + "gst_registration WHERE effective_from <= ? AND (effective_from > ? OR effective_from = "
                + "(SELECT MAX(effective_from) FROM gst_registration WHERE effective_from <= ?)) "
                + "ORDER BY effective_from")) {
            ps.setString(1, period.to().toString());
            ps.setString(2, period.from().toString());
            ps.setString(3, period.from().toString());
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    spanning.add(new GstRegistration(null, LocalDate.parse(rows.getString(3)),
                            GstRegistration.Scheme.valueOf(rows.getString(1)), rows.getString(2), null, null));
                }
            }
        }
        if (spanning.isEmpty() || spanning.get(0).effectiveFrom().isAfter(period.from())) {
            throw new ReturnException("The company has no GST registration from " + period.from());
        }
        String gstin = spanning.get(0).gstin();
        for (GstRegistration r : spanning) {
            if (r.scheme() != GstRegistration.Scheme.REGULAR) {
                throw new ReturnException("GSTR-1 and GSTR-3B are filed by a regular taxpayer; the company is "
                        + r.scheme().name().toLowerCase(java.util.Locale.ROOT) + " from " + r.effectiveFrom());
            }
            if (!r.gstin().equals(gstin)) {
                throw new ReturnException("The GSTIN changes within the period; file each registration's return");
            }
        }
        return gstin;
    }

    private static List<Doc> documents(Connection connection, ReturnPeriod period) throws SQLException {
        List<Doc> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT d.id, d.kind, d.number, d.status, "
                + "d.document_date, d.party_gstin, p.name, d.place_of_supply, d.supply_type, d.total_e4, "
                + "d.gst_scheme, d.original_document_id FROM sales_document d JOIN party p ON p.id = d.party_id "
                + "WHERE d.status IN ('POSTED', 'CANCELLED') AND d.document_date BETWEEN ? AND ? "
                + "ORDER BY d.number")) {
            ps.setString(1, period.from().toString());
            ps.setString(2, period.to().toString());
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    result.add(doc(rows));
                }
            }
        }
        return result;
    }

    private static Doc original(Connection connection, String id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT d.id, d.kind, d.number, d.status, "
                + "d.document_date, d.party_gstin, p.name, d.place_of_supply, d.supply_type, d.total_e4, "
                + "d.gst_scheme, d.original_document_id FROM sales_document d JOIN party p ON p.id = d.party_id "
                + "WHERE d.id = ?")) {
            ps.setString(1, id);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                return doc(rows);
            }
        }
    }

    private static Doc doc(ResultSet rows) throws SQLException {
        return new Doc(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                LocalDate.parse(rows.getString(5)), rows.getString(6), rows.getString(7), rows.getString(8),
                "INTER_STATE".equals(rows.getString(9)), money(rows.getLong(10)), rows.getString(11),
                rows.getString(12));
    }

    private static List<Line> lines(Connection connection, String documentId) throws SQLException {
        Map<String, BigDecimal[]> taxes = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT t.line_id, t.component, t.amount_e4 FROM "
                + "sales_tax_line t JOIN sales_document_line l ON l.id = t.line_id WHERE l.document_id = ?")) {
            ps.setString(1, documentId);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    BigDecimal[] t = taxes.computeIfAbsent(rows.getString(1), k -> new BigDecimal[]{ZERO, ZERO, ZERO,
                            ZERO});
                    int i = switch (rows.getString(2)) {
                        case "IGST" -> 0;
                        case "CGST" -> 1;
                        case "SGST", "UTGST" -> 2;
                        default -> 3;
                    };
                    t[i] = t[i].add(money(rows.getLong(3)));
                }
            }
        }
        List<Line> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, tax_category, gst_rate_e4, amount_e4, "
                + "quantity_e4, hsn_sac, uqc, description FROM sales_document_line WHERE document_id = ? "
                + "ORDER BY sequence")) {
            ps.setString(1, documentId);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    BigDecimal[] t = taxes.getOrDefault(rows.getString(1), new BigDecimal[]{ZERO, ZERO, ZERO, ZERO});
                    result.add(new Line(rows.getString(2),
                            MoneyMapper.fromStored(rows.getLong(3), Scale.TAX_RATE).stripTrailingZeros(),
                            money(rows.getLong(4)), MoneyMapper.fromStored(rows.getLong(5), Scale.QUANTITY),
                            rows.getString(6), rows.getString(7), rows.getString(8), t[0], t[1], t[2], t[3]));
                }
            }
        }
        return result;
    }

    private static List<Gstr1.RateItem> rateItems(List<Line> lines) {
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        for (Line line : lines) {
            if (!"TAXABLE".equals(line.category)) {
                continue;
            }
            BigDecimal[] a = byRate.computeIfAbsent(line.rate, k -> new BigDecimal[]{ZERO, ZERO, ZERO, ZERO, ZERO});
            a[0] = a[0].add(line.taxable);
            a[1] = a[1].add(line.igst);
            a[2] = a[2].add(line.cgst);
            a[3] = a[3].add(line.sgst);
            a[4] = a[4].add(line.cess);
        }
        List<Gstr1.RateItem> items = new ArrayList<>();
        byRate.forEach((rate, a) -> items.add(new Gstr1.RateItem(rate, a[0], a[1], a[2], a[3], a[4])));
        return items;
    }

    private static void addB2cs(Map<String, BigDecimal[]> b2cs, Doc doc, List<Gstr1.RateItem> items,
                                BigDecimal sign) {
        for (Gstr1.RateItem item : items) {
            String key = (doc.interState ? "INTER" : "INTRA") + "|" + doc.placeOfSupply + "|"
                    + item.rate().toPlainString();
            BigDecimal[] a = b2cs.computeIfAbsent(key, k -> new BigDecimal[]{ZERO, ZERO, ZERO, ZERO, ZERO});
            a[0] = a[0].add(item.taxable().multiply(sign));
            a[1] = a[1].add(item.igst().multiply(sign));
            a[2] = a[2].add(item.cgst().multiply(sign));
            a[3] = a[3].add(item.sgst().multiply(sign));
            a[4] = a[4].add(item.cess().multiply(sign));
        }
    }

    private static List<Gstr1.B2cs> b2csRows(Map<String, BigDecimal[]> b2cs) {
        List<Gstr1.B2cs> rows = new ArrayList<>();
        b2cs.forEach((key, a) -> {
            String[] k = key.split("\\|");
            rows.add(new Gstr1.B2cs(k[0].equals("INTER"), k[1], new BigDecimal(k[2]), a[0], a[1], a[2], a[3], a[4]));
        });
        return rows;
    }

    private static String nilKey(boolean interState, boolean registered) {
        return (interState ? "INTER" : "INTRA") + "|" + (registered ? "B2B" : "B2C");
    }

    private static List<Gstr1.Nil> nilRows(Map<String, BigDecimal[]> nil) {
        List<Gstr1.Nil> rows = new ArrayList<>();
        nil.forEach((key, a) -> rows.add(new Gstr1.Nil(key.startsWith("INTER|"), key.endsWith("B2B"), a[0], a[1],
                a[2])));
        return rows;
    }

    private static void addHsn(Map<String, BigDecimal[]> hsn, List<Gstr1.Issue> issues, Doc doc, Line line,
                               boolean registered, BigDecimal sign) {
        if (line.hsn == null) {
            boolean taxable = "TAXABLE".equals(line.category);
            issues.add(new Gstr1.Issue(taxable, doc.number, "\"" + line.description + "\" has no HSN/SAC code"
                    + (taxable ? "; the HSN summary needs one before the return is filed" : "")));
            return;
        }
        boolean service = line.hsn.startsWith("99");
        String uqc = service ? "NA" : line.uqc == null ? "OTH" : line.uqc;
        if (!service && line.uqc == null) {
            issues.add(new Gstr1.Issue(false, doc.number, "\"" + line.description + "\" is in a unit with no GST "
                    + "unit code; reported as OTH"));
        }
        String key = (registered ? "B2B" : "B2C") + "|" + line.hsn + "|" + uqc + "|" + line.rate.toPlainString();
        BigDecimal[] a = hsn.computeIfAbsent(key, k -> new BigDecimal[]{BigDecimal.ZERO, ZERO, ZERO, ZERO, ZERO,
                ZERO, ZERO});
        a[0] = a[0].add(service ? BigDecimal.ZERO : line.quantity.multiply(sign));
        a[1] = a[1].add(line.taxable.add(line.tax()).multiply(sign));
        a[2] = a[2].add(line.taxable.multiply(sign));
        a[3] = a[3].add(line.igst.multiply(sign));
        a[4] = a[4].add(line.cgst.multiply(sign));
        a[5] = a[5].add(line.sgst.multiply(sign));
        a[6] = a[6].add(line.cess.multiply(sign));
    }

    private static List<Gstr1.Hsn> hsnRows(Map<String, BigDecimal[]> hsn) {
        List<Gstr1.Hsn> rows = new ArrayList<>();
        hsn.forEach((key, a) -> {
            String[] k = key.split("\\|");
            rows.add(new Gstr1.Hsn(k[0].equals("B2B"), k[1], k[2], new BigDecimal(k[3]),
                    a[0].stripTrailingZeros(), a[1], a[2], a[3], a[4], a[5], a[6]));
        });
        return rows;
    }

    private static List<Gstr1.DocRange> docRanges(Map<String, List<Doc>> issued) {
        List<Gstr1.DocRange> ranges = new ArrayList<>();
        issued.forEach((nature, docs) -> {
            if (docs.isEmpty()) {
                return;
            }
            List<String> numbers = docs.stream().map(Doc::number).sorted(Comparator.naturalOrder()).toList();
            int cancelled = (int) docs.stream().filter(d -> "CANCELLED".equals(d.status)).count();
            ranges.add(new Gstr1.DocRange(nature, numbers.get(0), numbers.get(numbers.size() - 1), docs.size(),
                    cancelled));
        });
        return ranges;
    }

    private static BigDecimal money(long stored) {
        return MoneyMapper.fromStored(stored, Scale.MONEY).setScale(2);
    }
}
