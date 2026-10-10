package com.accubook.inventory;

/**
 * P8-1: how an item's issues are costed. LIFO is deliberately absent - AS 2 and
 * Ind AS 2 do not permit it, and the reference's "LIFO" was a report-time
 * reversal of a list no auditor would accept.
 */
public enum CostingMethod {
    /** Moving average, recalculated on every receipt (P8-2). */
    WEIGHTED_AVERAGE,
    FIFO
}
