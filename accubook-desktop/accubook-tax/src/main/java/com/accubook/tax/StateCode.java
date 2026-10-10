package com.accubook.tax;

/**
 * A GST state or union-territory code, from the rule pack - the one table the
 * reference kept three disagreeing copies of.
 *
 * @param code two digits, as in a GSTIN's first two characters
 */
public record StateCode(String code, String name, Kind kind) {

    public enum Kind {
        STATE,
        /** A union territory with its own UTGST (no legislature). */
        UNION_TERRITORY,
        /** 97 Other Territory, 99 Centre Jurisdiction. */
        SPECIAL
    }
}
