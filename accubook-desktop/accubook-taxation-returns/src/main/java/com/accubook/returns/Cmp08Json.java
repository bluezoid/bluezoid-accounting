package com.accubook.returns;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CMP-08 as kept on filing. CMP-08 is entered on the portal, not uploaded, so
 * this is AccuBook's own record of what was filed ({@code format:
 * accubook-cmp08/1}), with the portal's table numbers.
 */
final class Cmp08Json {

    private Cmp08Json() {
    }

    static String write(Cmp08 r) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "accubook-cmp08/1");
        root.put("gstin", r.gstin());
        root.put("fp", r.period().fp());
        root.put("category", r.category().name());
        Map<String, Object> outward = new LinkedHashMap<>();
        outward.put("turnover", Json.amount(r.outwardTurnover()));
        outward.put("base", Json.amount(r.outwardBase()));
        outward.put("camt", Json.amount(r.compositionCgst()));
        outward.put("samt", Json.amount(r.compositionSgst()));
        root.put("outward_3_1", outward);
        Map<String, Object> rcm = new LinkedHashMap<>();
        rcm.put("txval", Json.amount(r.reverseCharge().taxable()));
        rcm.put("iamt", Json.amount(r.reverseCharge().igst()));
        rcm.put("camt", Json.amount(r.reverseCharge().cgst()));
        rcm.put("samt", Json.amount(r.reverseCharge().sgst()));
        rcm.put("csamt", Json.amount(r.reverseCharge().cess()));
        root.put("inward_rcm_3_2", rcm);
        root.put("total_payable", Json.amount(r.totalPayable()));
        return Json.write(root);
    }
}
