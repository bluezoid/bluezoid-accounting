package com.accubook.tax;

import java.time.LocalDate;

/**
 * The company's GST position from a date.
 *
 * @param gstin     null when unregistered
 * @param stateCode the supplier's state, which decides intra- or inter-state
 */
public record GstRegistration(String id, LocalDate effectiveFrom, Scheme scheme, String gstin, String stateCode,
                              String rulePackVersion) {

    public enum Scheme {
        REGULAR,
        /** Bills of supply, no ITC, CMP-08 instead of GSTR-1/3B. */
        COMPOSITION,
        UNREGISTERED
    }
}
