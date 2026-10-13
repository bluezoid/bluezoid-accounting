package com.accubook.returns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GSTR-9 as kept on filing, in AccuBook's own shape ({@code format:
 * accubook-gstr9/1}) keyed by the form's table numbers. The portal's offline
 * tool takes these figures; its own JSON is not reproduced here (V-1).
 */
final class Gstr9Json {

    private Gstr9Json() {
    }

    static String write(Gstr9 r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "accubook-gstr9/1");
        root.put("gstin", r.gstin());
        root.put("fy", r.from().getYear() + "-" + (r.to().getYear() % 100));
        root.put("4A_b2c", tax(r.b2c()));
        root.put("4B_b2b", tax(r.b2b()));
        root.put("4I_credit_notes", tax(r.creditNotes()));
        root.put("5D_exempt", Json.amount(r.exempt()));
        root.put("5E_nil_rated", Json.amount(r.nilRated()));
        root.put("5F_non_gst", Json.amount(r.nonGst()));
        root.put("6A_itc_as_filed", tax(r.itcAsFiled()));
        root.put("6B_itc_inward", tax(r.itcInward()));
        root.put("6D_itc_reverse_charge", tax(r.itcReverseCharge()));
        root.put("6J_difference", tax(r.itcDifference()));
        root.put("8A_itc_as_2b", tax(r.itcAs2b()));
        root.put("8D_difference", tax(r.itc2bDifference()));
        root.put("9_tax_payable", tax(r.taxPayable()));
        List<Object> hsn = new ArrayList<>();
        for (Gstr1.Hsn h : r.hsn()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hsn_sc", h.hsn());
            m.put("uqc", h.uqc());
            m.put("qty", h.quantity());
            m.put("rt", h.rate());
            m.put("txval", Json.amount(h.taxable()));
            m.put("iamt", Json.amount(h.igst()));
            m.put("camt", Json.amount(h.cgst()));
            m.put("samt", Json.amount(h.sgst()));
            m.put("csamt", Json.amount(h.cess()));
            hsn.add(m);
        }
        root.put("17_hsn", hsn);
        return Json.write(root);
    }

    private static Map<String, Object> tax(Gstr3b.Tax t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("txval", Json.amount(t.taxable()));
        m.put("iamt", Json.amount(t.igst()));
        m.put("camt", Json.amount(t.cgst()));
        m.put("samt", Json.amount(t.sgst()));
        m.put("csamt", Json.amount(t.cess()));
        return m;
    }
}
