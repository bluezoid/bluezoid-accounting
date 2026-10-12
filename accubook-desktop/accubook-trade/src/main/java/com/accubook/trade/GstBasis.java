package com.accubook.trade;

import com.accubook.tax.GstRegistration;
import com.accubook.tax.SupplyType;

/**
 * What a document was taxed on, recorded on the draft and locked at posting
 * (P9-5). Reports read it; nothing re-derives it.
 *
 * @param scheme          the company's registration on the document date
 * @param partyGstin      the party's GSTIN as it stood, or null if unregistered
 * @param reverseCharge   purchase only: the company pays the GST itself
 * @param rulePackVersion the rule pack the tax was computed under
 */
public record GstBasis(GstRegistration.Scheme scheme, String placeOfSupply, String supplierState,
                       SupplyType supplyType, String partyGstin, boolean reverseCharge, String rulePackVersion) {
}
