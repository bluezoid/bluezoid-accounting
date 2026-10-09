package com.accubook.licensing.state;

/**
 * What the subscription permits right now.
 *
 * <p>Implements the state machine in
 * {@code docs/phases/PHASE_3_STATE_MACHINE.md}, which resolves the ambiguity
 * raised in review: <strong>the 14-day window sits before the subscription end
 * date, not after it.</strong>
 *
 * <p>Days T-14 to T are a renewal <em>warning</em> period during which the
 * subscription is still active and the application fully functional. At the end
 * date the application becomes read-only immediately. There is no post-expiry
 * period of continued use.
 *
 * <p>Only {@link #ACTIVE} and {@link #RENEWAL_WINDOW} permit mutation. Reading
 * and exporting are permitted in every state except {@link #UNLICENSED},
 * because a customer's books remain theirs regardless of what they have paid.
 */
public enum SubscriptionState {

    /** Never activated. The activation screen is all that is available. */
    UNLICENSED(false, false),

    /** Subscription current. Full use. */
    ACTIVE(true, true),

    /**
     * Within the renewal window before the end date.
     *
     * <p>Functionally identical to {@link #ACTIVE}; it exists only so the UI can
     * warn. It grants no extra time.
     */
    RENEWAL_WINDOW(true, true),

    /** Past the end date. Read-only. */
    EXPIRED(false, true),

    /** Suspended by an administrator. Read-only. */
    SUSPENDED(false, true),

    /**
     * The entitlement is missing, unreadable, tampered with, or bound to a
     * different machine. Read-only until the customer reactivates.
     *
     * <p>Never means the data is gone: company databases are untouched, and
     * export still works.
     */
    INVALID(false, true);

    private final boolean mutationAllowed;
    private final boolean readAllowed;

    SubscriptionState(boolean mutationAllowed, boolean readAllowed) {
        this.mutationAllowed = mutationAllowed;
        this.readAllowed = readAllowed;
    }

    /** True only for ACTIVE and RENEWAL_WINDOW. */
    public boolean allowsMutation() {
        return mutationAllowed;
    }

    /** True for every state except UNLICENSED. */
    public boolean allowsRead() {
        return readAllowed;
    }

    /** True when the customer should be prompted to renew. */
    public boolean needsRenewal() {
        return this == RENEWAL_WINDOW || this == EXPIRED;
    }
}
