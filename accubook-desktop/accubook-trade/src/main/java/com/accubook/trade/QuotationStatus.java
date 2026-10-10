package com.accubook.trade;

/** P7-19. EXPIRED is derived from the valid-until date, never stored. */
public enum QuotationStatus {
    DRAFT, SENT, ACCEPTED, REJECTED, CONVERTED, EXPIRED
}
