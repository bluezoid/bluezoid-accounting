package com.accubook.accounting.chart;

/**
 * A chart-of-accounts operation was refused.
 *
 * <p>Carries a message aimed at the person who attempted it, since every refusal
 * here is a governance rule rather than a system fault - deleting a system
 * ledger, moving an account across natures after it has been posted to, and so
 * on. The customer needs to know what to do instead.
 */
public class ChartException extends RuntimeException {
    public ChartException(String message) {
        super(message);
    }

    public ChartException(String message, Throwable cause) {
        super(message, cause);
    }
}
