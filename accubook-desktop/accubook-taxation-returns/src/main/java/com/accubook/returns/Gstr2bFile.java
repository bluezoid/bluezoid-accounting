package com.accubook.returns;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A GSTR-2B statement as downloaded from the portal: its B2B invoices and CDNR
 * notes. Read from {@code data.docdata}, with the 2B field names {@code dt} and
 * {@code igst/cgst/sgst/cess}; the GSTR-1 style names ({@code idt},
 * {@code iamt/camt/samt/csamt}) are accepted too, since files from other tools
 * use them.
 */
record Gstr2bFile(ReturnPeriod period, String gstin, List<Doc> documents) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    record Doc(String type, String ctin, String tradeName, String number, LocalDate date, String placeOfSupply,
               boolean reverseCharge, boolean itcAvailable, BigDecimal value, BigDecimal taxable, BigDecimal igst,
               BigDecimal cgst, BigDecimal sgst, BigDecimal cess) {
    }

    /**
     * P9-18: the number as compared - upper case, without spaces, {@code / - .},
     * and without leading zeros in any run of digits ("INV/0012" and "inv-12"
     * are both "INV12").
     */
    static String key(String number) {
        return number.toUpperCase(Locale.ROOT).replaceAll("[\\s/.\\-]", "").replaceAll("(?<![0-9])0+(?=[0-9])", "");
    }

    static Gstr2bFile parse(String json) {
        Map<String, Object> root = object(JsonReader.read(json), "the file");
        Map<String, Object> data = root.containsKey("data") ? object(root.get("data"), "data") : root;
        String rtnprd = text(data, "rtnprd");
        if (!rtnprd.matches("\\d{6}")) {
            throw new ReturnException("GSTR-2B period \"" + rtnprd + "\" is not MMYYYY");
        }
        ReturnPeriod period = ReturnPeriod.month(Integer.parseInt(rtnprd.substring(2)),
                Integer.parseInt(rtnprd.substring(0, 2)));
        String gstin = text(data, "gstin").toUpperCase(Locale.ROOT);
        Map<String, Object> docdata = data.containsKey("docdata") ? object(data.get("docdata"), "docdata") : Map.of();
        List<Doc> docs = new ArrayList<>();
        for (Object s : list(docdata.get("b2b"))) {
            Map<String, Object> supplier = object(s, "b2b");
            for (Object i : list(supplier.get("inv"))) {
                docs.add(doc("INV", supplier, object(i, "inv"), "inum"));
            }
        }
        for (Object s : list(docdata.get("cdnr"))) {
            Map<String, Object> supplier = object(s, "cdnr");
            for (Object n : list(supplier.get("nt"))) {
                Map<String, Object> note = object(n, "nt");
                String type = optional(note, "typ", optional(note, "ntty", "C"));
                docs.add(doc("D".equals(type) ? "DN" : "CN", supplier, note,
                        note.containsKey("ntnum") ? "ntnum" : "nt_num"));
            }
        }
        return new Gstr2bFile(period, gstin, docs);
    }

    private static Doc doc(String type, Map<String, Object> supplier, Map<String, Object> d, String numberField) {
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal igst = BigDecimal.ZERO;
        BigDecimal cgst = BigDecimal.ZERO;
        BigDecimal sgst = BigDecimal.ZERO;
        BigDecimal cess = BigDecimal.ZERO;
        for (Object item : list(d.get("items"))) {
            Map<String, Object> m = object(item, "items");
            taxable = taxable.add(amount(m, "txval", null));
            igst = igst.add(amount(m, "igst", "iamt"));
            cgst = cgst.add(amount(m, "cgst", "camt"));
            sgst = sgst.add(amount(m, "sgst", "samt"));
            cess = cess.add(amount(m, "cess", "csamt"));
        }
        String dateText = d.containsKey("dt") ? text(d, "dt") : text(d, d.containsKey("idt") ? "idt" : "nt_dt");
        LocalDate date;
        try {
            date = LocalDate.parse(dateText, DATE);
        } catch (DateTimeParseException e) {
            throw new ReturnException("GSTR-2B date \"" + dateText + "\" is not DD-MM-YYYY", e);
        }
        return new Doc(type, text(supplier, "ctin").toUpperCase(Locale.ROOT), optional(supplier, "trdnm", null),
                text(d, numberField), date, optional(d, "pos", null), "Y".equals(optional(d, "rev", "N")),
                !"N".equals(optional(d, "itcavl", "Y")), amount(d, "val", null), taxable, igst, cgst, sgst, cess);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String what) {
        if (!(value instanceof Map)) {
            throw new ReturnException("GSTR-2B: " + what + " is not an object");
        }
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> l)) {
            throw new ReturnException("GSTR-2B: expected a list");
        }
        return l;
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new ReturnException("GSTR-2B: \"" + key + "\" is missing");
        }
        return v instanceof BigDecimal d ? d.toPlainString() : v.toString().strip();
    }

    private static String optional(Map<String, Object> m, String key, String otherwise) {
        Object v = m.get(key);
        return v == null ? otherwise : v.toString().strip();
    }

    private static BigDecimal amount(Map<String, Object> m, String key, String alternative) {
        Object v = m.containsKey(key) ? m.get(key) : alternative == null ? null : m.get(alternative);
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (!(v instanceof BigDecimal d)) {
            throw new ReturnException("GSTR-2B: \"" + key + "\" is not a number");
        }
        if (d.stripTrailingZeros().scale() > 2) {
            throw new ReturnException("GSTR-2B: \"" + key + "\" " + d.toPlainString()
                    + " has more than two decimals");
        }
        return d;
    }
}
