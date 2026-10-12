package com.accubook.returns;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * GSTR-1 in the GST portal's offline-upload JSON shape (P9-15): field names
 * {@code ctin}, {@code inum}, {@code idt}, {@code itms}, … as the offline tool
 * writes them. Built from the published format, not from a GSTN schema file:
 * a generated file must pass the portal's offline tool before release
 * (V-1). The tool's own {@code version} and {@code hash} fields are left for
 * it to add.
 */
final class Gstr1Json {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private Gstr1Json() {
    }

    static String write(Gstr1 r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("gstin", r.gstin());
        root.put("fp", r.period().fp());
        root.put("b2b", grouped(r.b2b(), Gstr1.B2b::ctin, b -> invoice(b.number(), b.date(), b.value(),
                b.placeOfSupply(), true, b.items())));
        root.put("b2cl", grouped(r.b2cl(), Gstr1.B2cl::placeOfSupply, b -> invoice(b.number(), b.date(), b.value(),
                null, false, b.items())));
        List<Object> b2cs = new ArrayList<>();
        for (Gstr1.B2cs row : r.b2cs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sply_ty", row.interState() ? "INTER" : "INTRA");
            m.put("pos", row.placeOfSupply());
            m.put("typ", "OE");
            m.put("rt", row.rate());
            m.put("txval", Json.amount(row.taxable()));
            m.put("iamt", Json.amount(row.igst()));
            m.put("camt", Json.amount(row.cgst()));
            m.put("samt", Json.amount(row.sgst()));
            m.put("csamt", Json.amount(row.cess()));
            b2cs.add(m);
        }
        root.put("b2cs", b2cs);
        root.put("cdnr", grouped(r.cdnr(), Gstr1.Cdnr::ctin, n -> note(n.number(), n.date(), n.value(),
                n.placeOfSupply(), n.items(), null)));
        List<Object> cdnur = new ArrayList<>();
        for (Gstr1.Cdnur n : r.cdnur()) {
            cdnur.add(note(n.number(), n.date(), n.value(), n.placeOfSupply(), n.items(), "B2CL"));
        }
        root.put("cdnur", cdnur);
        List<Object> nil = new ArrayList<>();
        for (Gstr1.Nil row : r.nil()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sply_ty", (row.interState() ? "INTR" : "INTRA") + (row.registered() ? "B2B" : "B2C"));
            m.put("nil_amt", Json.amount(row.nilRated()));
            m.put("expt_amt", Json.amount(row.exempt()));
            m.put("ngsup_amt", Json.amount(row.nonGst()));
            nil.add(m);
        }
        root.put("nil", Map.of("inv", nil));
        root.put("hsn", hsn(r.hsn()));
        root.put("doc_issue", docIssue(r.docs()));
        return Json.write(root);
    }

    private static <T> List<Object> grouped(List<T> rows, Function<T, String> key, Function<T, Object> entry) {
        Map<String, List<Object>> groups = new LinkedHashMap<>();
        for (T row : rows) {
            groups.computeIfAbsent(key.apply(row), k -> new ArrayList<>()).add(entry.apply(row));
        }
        List<Object> result = new ArrayList<>();
        groups.forEach((k, list) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(rows.get(0) instanceof Gstr1.B2cl ? "pos" : "ctin", k);
            m.put(rows.get(0) instanceof Gstr1.Cdnr ? "nt" : "inv", list);
            result.add(m);
        });
        return result;
    }

    private static Map<String, Object> invoice(String number, java.time.LocalDate date, BigDecimal value, String pos,
                                               boolean b2b, List<Gstr1.RateItem> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inum", number);
        m.put("idt", date.format(DATE));
        m.put("val", Json.amount(value));
        if (b2b) {
            m.put("pos", pos);
            m.put("rchrg", "N");
            m.put("inv_typ", "R");
        }
        m.put("itms", items(items));
        return m;
    }

    private static Map<String, Object> note(String number, java.time.LocalDate date, BigDecimal value, String pos,
                                            List<Gstr1.RateItem> items, String unregisteredType) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (unregisteredType != null) {
            m.put("typ", unregisteredType);
        }
        m.put("ntty", "C");
        m.put("nt_num", number);
        m.put("nt_dt", date.format(DATE));
        m.put("val", Json.amount(value));
        m.put("pos", pos);
        if (unregisteredType == null) {
            m.put("rchrg", "N");
            m.put("inv_typ", "R");
        }
        m.put("itms", items(items));
        return m;
    }

    /** Items are numbered rate x 100 + 1 (1801 for 18%), as the offline tool numbers them. */
    private static List<Object> items(List<Gstr1.RateItem> items) {
        List<Object> result = new ArrayList<>();
        for (Gstr1.RateItem item : items) {
            Map<String, Object> det = new LinkedHashMap<>();
            det.put("txval", Json.amount(item.taxable()));
            det.put("rt", item.rate());
            det.put("iamt", Json.amount(item.igst()));
            det.put("camt", Json.amount(item.cgst()));
            det.put("samt", Json.amount(item.sgst()));
            det.put("csamt", Json.amount(item.cess()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("num", item.rate().multiply(BigDecimal.valueOf(100)).intValue() + 1);
            m.put("itm_det", det);
            result.add(m);
        }
        return result;
    }

    private static Map<String, Object> hsn(List<Gstr1.Hsn> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hsn_b2b", hsnRows(rows.stream().filter(Gstr1.Hsn::registered).toList()));
        m.put("hsn_b2c", hsnRows(rows.stream().filter(h -> !h.registered()).toList()));
        return m;
    }

    private static List<Object> hsnRows(List<Gstr1.Hsn> rows) {
        List<Object> result = new ArrayList<>();
        int num = 1;
        for (Gstr1.Hsn h : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("num", num++);
            m.put("hsn_sc", h.hsn());
            m.put("uqc", h.uqc());
            m.put("qty", h.quantity());
            m.put("rt", h.rate());
            m.put("txval", Json.amount(h.taxable()));
            m.put("iamt", Json.amount(h.igst()));
            m.put("camt", Json.amount(h.cgst()));
            m.put("samt", Json.amount(h.sgst()));
            m.put("csamt", Json.amount(h.cess()));
            result.add(m);
        }
        return result;
    }

    private static Map<String, Object> docIssue(List<Gstr1.DocRange> docs) {
        List<Object> det = new ArrayList<>();
        for (Gstr1.DocRange d : docs) {
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("num", 1);
            range.put("from", d.from());
            range.put("to", d.to());
            range.put("totnum", d.total());
            range.put("cancel", d.cancelled());
            range.put("net_issue", d.netIssued());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("doc_num", d.nature().startsWith("Invoices") ? 1 : 5);
            m.put("docs", List.of(range));
            det.add(m);
        }
        return Map.of("doc_det", det);
    }
}
