package com.accubook.returns;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GSTR-3B in the portal's JSON field names ({@code sup_details},
 * {@code itc_elg}, …), built from the published format and to be validated on
 * the portal before release (V-1).
 */
final class Gstr3bJson {

    private Gstr3bJson() {
    }

    static String write(Gstr3b r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("gstin", r.gstin());
        root.put("ret_period", r.period().fp());
        Map<String, Object> sup = new LinkedHashMap<>();
        sup.put("osup_det", tax(r.outward(), true));
        sup.put("osup_zero", tax(Gstr3b.Tax.NIL, true));
        sup.put("osup_nil_exmp", Map.of("txval", Json.amount(r.nilExemptOutward())));
        sup.put("isup_rev", tax(r.reverseChargeInward(), true));
        sup.put("osup_nongst", Map.of("txval", Json.amount(r.nonGstOutward())));
        root.put("sup_details", sup);
        List<Object> unreg = new ArrayList<>();
        for (Gstr3b.UnregisteredInterState u : r.interStateUnregistered()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pos", u.placeOfSupply());
            m.put("txval", Json.amount(u.taxable()));
            m.put("iamt", Json.amount(u.igst()));
            unreg.add(m);
        }
        Map<String, Object> inter = new LinkedHashMap<>();
        inter.put("unreg_details", unreg);
        inter.put("comp_details", List.of());
        inter.put("uin_details", List.of());
        root.put("inter_sup", inter);
        Map<String, Object> itc = new LinkedHashMap<>();
        itc.put("itc_avl", List.of(typed("IMPG", Gstr3b.Tax.NIL), typed("IMPS", Gstr3b.Tax.NIL),
                typed("ISRC", r.itcReverseCharge()), typed("ISD", Gstr3b.Tax.NIL), typed("OTH", r.itcOther())));
        itc.put("itc_rev", List.of(typed("RUL", Gstr3b.Tax.NIL), typed("OTH", Gstr3b.Tax.NIL)));
        itc.put("itc_net", tax(r.totalItc(), false));
        itc.put("itc_inelg", List.of(typed("RUL", Gstr3b.Tax.NIL), typed("OTH", Gstr3b.Tax.NIL)));
        root.put("itc_elg", itc);
        Gstr3b.Inward in = r.exemptInward();
        root.put("inward_sup", Map.of("isup_details", List.of(inward("GST", in.exemptNilInter(), in.exemptNilIntra()),
                inward("NONGST", in.nonGstInter(), in.nonGstIntra()))));
        return Json.write(root);
    }

    private static Map<String, Object> tax(Gstr3b.Tax t, boolean withTaxable) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (withTaxable) {
            m.put("txval", Json.amount(t.taxable()));
        }
        m.put("iamt", Json.amount(t.igst()));
        m.put("camt", Json.amount(t.cgst()));
        m.put("samt", Json.amount(t.sgst()));
        m.put("csamt", Json.amount(t.cess()));
        return m;
    }

    private static Map<String, Object> typed(String type, Gstr3b.Tax t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ty", type);
        m.putAll(tax(t, false));
        return m;
    }

    private static Map<String, Object> inward(String type, BigDecimal inter, BigDecimal intra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ty", type);
        m.put("inter", Json.amount(inter));
        m.put("intra", Json.amount(intra));
        return m;
    }
}
