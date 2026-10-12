package com.accubook.tax;

/** One of the taxes a GST line can carry. Each posts to its own ledger (P9-7). */
public enum GstComponent {
    CGST,
    SGST,
    /** In place of SGST in a union territory without a legislature. */
    UTGST,
    IGST,
    CESS
}
