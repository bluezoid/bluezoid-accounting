package com.accubook.tax;

/**
 * What a composition dealer does, which decides its rate and what the rate is
 * charged on (P9-20).
 */
public enum CompositionCategory {
    /** Buys and sells goods: 1% of taxable supplies only (R-24). */
    TRADER,
    MANUFACTURER,
    /** Restaurant service (no alcohol). */
    RESTAURANT,
    /** Services, or mixed supplies, under s.10(2A). */
    SERVICES
}
