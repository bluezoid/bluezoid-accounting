package com.accubook.tax;

/**
 * How GST treats a line (P9-2). Every line carries one explicitly; a missing
 * rate is never read as nil.
 */
public enum TaxCategory {
    /** Taxed at a slab rate above zero. */
    TAXABLE,
    /** Taxed at 0% - reported separately from exempt supplies in GSTR-1 and 3B. */
    NIL_RATED,
    /** Exempt by notification. */
    EXEMPT,
    /** Outside GST altogether (e.g. petrol, alcohol for human consumption). */
    NON_GST;

    public boolean taxed() {
        return this == TAXABLE;
    }
}
