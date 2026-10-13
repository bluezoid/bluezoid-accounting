package com.accubook.tax;

import java.time.LocalDate;

/**
 * The company's GST position from a date.
 *
 * @param gstin     null when unregistered
 * @param stateCode the supplier's state, which decides intra- or inter-state
 * @param compositionCategory for a composition registration, what decides its rate (P9-20); else null
 */
public record GstRegistration(String id, LocalDate effectiveFrom, Scheme scheme, String gstin, String stateCode,
                              String rulePackVersion, CompositionCategory compositionCategory) {

    public GstRegistration(String id, LocalDate effectiveFrom, Scheme scheme, String gstin, String stateCode,
                           String rulePackVersion) {
        this(id, effectiveFrom, scheme, gstin, stateCode, rulePackVersion, null);
    }

    public enum Scheme {
        REGULAR,
        /** Bills of supply, no ITC, CMP-08 instead of GSTR-1/3B. */
        COMPOSITION,
        UNREGISTERED
    }
}
