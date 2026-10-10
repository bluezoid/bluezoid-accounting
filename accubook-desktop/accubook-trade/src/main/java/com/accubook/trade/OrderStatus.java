package com.accubook.trade;

/**
 * An order's position. Only DRAFT, CLOSED and CANCELLED are stored; OPEN,
 * PARTIAL and FULFILLED are derived from linked, posted invoice or bill lines.
 */
public enum OrderStatus {
    DRAFT, OPEN, PARTIAL, FULFILLED, CLOSED, CANCELLED
}
