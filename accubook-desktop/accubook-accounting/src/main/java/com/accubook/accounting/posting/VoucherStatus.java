package com.accubook.accounting.posting;

/**
 * Where a voucher sits in its lifecycle.
 *
 * <p>Replaces the independent {@code status} + {@code is_posted} pair, which
 * could represent contradictions - a CANCELLED voucher that was nonetheless
 * posted would sit in the trial balance while every screen showed it cancelled.
 * One enum with explicit transitions cannot express that.
 *
 * <pre>
 *   DRAFT ──post──&gt; POSTED ──reverse──&gt; REVERSED
 *     │
 *   cancel
 *     │
 *     v
 *  CANCELLED
 * </pre>
 */
public enum VoucherStatus {

    /** Being prepared. Editable, and absent from the ledger. */
    DRAFT,

    /** In the books. Immutable from here on. */
    POSTED,

    /**
     * Posted, then reversed by a contra voucher.
     *
     * <p>Still counts towards the trial balance: it and its reversal sum to
     * zero. "Reversed" is a display fact, not an erasure - a statutory record is
     * never removed because it was wrong.
     */
    REVERSED,

    /** Abandoned before posting. Never entered the ledger. */
    CANCELLED;

    /** True when the voucher affects the ledger. */
    public boolean isPosted() {
        return this == POSTED || this == REVERSED;
    }

    /** True when no further transition is possible. */
    public boolean isTerminal() {
        return this == REVERSED || this == CANCELLED;
    }

    /**
     * Whether {@code this -> target} is a legal transition.
     *
     * <p>Notably POSTED -> CANCELLED is refused. Cancelling something already in
     * the ledger would silently restate closed books; the answer is a reversal,
     * which leaves both records visible.
     */
    public boolean canTransitionTo(VoucherStatus target) {
        return switch (this) {
            case DRAFT -> target == POSTED || target == CANCELLED;
            case POSTED -> target == REVERSED;
            case REVERSED, CANCELLED -> false;
        };
    }
}
