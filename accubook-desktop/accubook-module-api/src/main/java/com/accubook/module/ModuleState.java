package com.accubook.module;

/**
 * Whether a feature module may be used, and how.
 *
 * <p>This is the entire licensing surface a feature module ever sees. A module
 * asks {@link ModuleStateProvider#moduleState(String)} and acts on the answer;
 * it never learns what a licence, a signature, a machine fingerprint or an
 * activation server is.
 *
 * <p>That separation is the point. In the reference application the equivalent
 * check was left to each handler, and 55 of 71 mutating routes forgot it - a
 * VIEWER could post journal vouchers and run payroll. Giving modules one narrow
 * contract, with the enforcement behind it, is what stops that drift.
 */
public enum ModuleState {

    /** Packaged, entitled, and the subscription is current. Full use. */
    ACTIVE,

    /**
     * Packaged, but mutation is not permitted.
     *
     * <p>Either the plan no longer includes the module, or the subscription has
     * expired or been suspended. Existing data stays visible and exportable -
     * history is never withheld.
     */
    READ_ONLY,

    /**
     * Not packaged in this build, though data may exist from a previous one.
     *
     * <p>A customer who downgrades keeps their records; the code that wrote them
     * is simply absent, so the data is reachable through export and a generic
     * viewer rather than the module's own screens.
     */
    DORMANT,

    /** Not packaged and no data was ever written. Nothing to show. */
    ABSENT;

    /** True only for {@link #ACTIVE}. */
    public boolean allowsMutation() {
        return this == ACTIVE;
    }

    /**
     * True unless the module is entirely absent.
     *
     * <p>Reading is never gated by entitlement: an expired subscription or a
     * downgrade must never hide a customer's own history from them.
     */
    public boolean allowsRead() {
        return this != ABSENT;
    }
}
