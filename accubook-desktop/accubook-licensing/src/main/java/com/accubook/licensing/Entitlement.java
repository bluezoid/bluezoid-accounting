package com.accubook.licensing;

import com.accubook.licensing.state.SubscriptionState;
import com.accubook.licensing.token.EntitlementToken;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleStateProvider;

import java.time.LocalDate;
import java.util.Set;

/**
 * What this installation may do, resolved once and then immutable.
 *
 * <h2>Verified once, read many times</h2>
 * Ed25519 verification costs about 1.5 ms - measured. Running it in front of
 * every accounting operation would put a third of a second of signature checking
 * behind a screen that posts two hundred lines. So the signature is checked when
 * the entitlement is <em>loaded</em>, and the result is frozen into this object.
 * Every later question is a set lookup.
 *
 * <p>This is not a weakening. What signature verification defends against is a
 * <em>tampered file</em>, and a file is only read when it is loaded. An attacker
 * who can rewrite process memory has already defeated any client-side check, as
 * the threat model states plainly.
 *
 * <h2>The only thing feature modules see</h2>
 * This class implements {@link ModuleStateProvider}, whose single method returns
 * a {@link ModuleState}. A feature module never learns that licences,
 * signatures, fingerprints or activation servers exist - which is what stops
 * licensing logic reappearing inside every module.
 */
public final class Entitlement implements ModuleStateProvider {

    private final SubscriptionState subscriptionState;
    private final Set<String> entitledModules;
    private final Set<String> packagedModules;
    private final String plan;
    private final LocalDate subscriptionEnd;
    private final long daysUntilExpiry;
    private final String invalidReason;

    private Entitlement(SubscriptionState subscriptionState,
                        Set<String> entitledModules,
                        Set<String> packagedModules,
                        String plan,
                        LocalDate subscriptionEnd,
                        long daysUntilExpiry,
                        String invalidReason) {
        this.subscriptionState = subscriptionState;
        this.entitledModules = Set.copyOf(entitledModules);
        this.packagedModules = Set.copyOf(packagedModules);
        this.plan = plan;
        this.subscriptionEnd = subscriptionEnd;
        this.daysUntilExpiry = daysUntilExpiry;
        this.invalidReason = invalidReason;
    }

    /**
     * Resolve a verified token against today's date and this build's contents.
     *
     * @param packagedModules module ids physically present in this build
     * @param today           evaluated against the tamper-resistant clock, not
     *                        the raw system clock
     */
    public static Entitlement resolve(EntitlementToken token, Set<String> packagedModules, LocalDate today) {
        SubscriptionState state = evaluate(token, today);
        long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, token.subscriptionEnd());
        return new Entitlement(state, token.modules(), packagedModules,
                token.plan(), token.subscriptionEnd(), daysLeft, null);
    }

    /**
     * An installation with no usable entitlement.
     *
     * <p>Read-only, and explicitly <strong>not</strong> destructive: company
     * databases are untouched, and export still works. "Reactivate" never means
     * "your data is gone".
     */
    public static Entitlement invalid(String reason, Set<String> packagedModules) {
        return new Entitlement(SubscriptionState.INVALID, Set.of(), packagedModules,
                null, null, 0, reason);
    }

    /** An installation that has never been activated. */
    public static Entitlement unlicensed(Set<String> packagedModules) {
        return new Entitlement(SubscriptionState.UNLICENSED, Set.of(), packagedModules,
                null, null, 0, null);
    }

    /**
     * Where the subscription sits relative to its end date.
     *
     * <p>The renewal window precedes the end date; crossing the end date means
     * read-only immediately.
     */
    private static SubscriptionState evaluate(EntitlementToken token, LocalDate today) {
        if (token.suspended()) {
            return SubscriptionState.SUSPENDED;
        }
        if (today.isBefore(token.subscriptionStart())) {
            // Issued for a period that has not begun. Refused rather than
            // treated as active, which would let a post-dated token be used now.
            return SubscriptionState.INVALID;
        }
        if (today.isAfter(token.subscriptionEnd())) {
            return SubscriptionState.EXPIRED;
        }
        if (!today.isBefore(token.renewalWindowStart())) {
            return SubscriptionState.RENEWAL_WINDOW;
        }
        return SubscriptionState.ACTIVE;
    }

    // ---- ModuleStateProvider: the entire surface a feature module sees ----

    @Override
    public ModuleState moduleState(String moduleId) {
        boolean packaged = packagedModules.contains(moduleId);
        boolean entitled = entitledModules.contains(moduleId);

        if (!packaged) {
            // Not in this build. If the customer previously had it, their data
            // is still there and reachable by export, so this is DORMANT rather
            // than ABSENT; a module that was never entitled is ABSENT.
            return entitled ? ModuleState.DORMANT
                    : (hasEverBeenEntitled(moduleId) ? ModuleState.DORMANT : ModuleState.ABSENT);
        }
        if (!entitled) {
            // Packaged but not in the plan: history stays visible, nothing new.
            return ModuleState.READ_ONLY;
        }
        return subscriptionState.allowsMutation() ? ModuleState.ACTIVE : ModuleState.READ_ONLY;
    }

    /**
     * Whether data may exist for a module this build does not contain.
     *
     * <p>Phase 3 cannot know: the record of which modules a company has used
     * lives in the company database, which arrives with the module registry in
     * Phase 4. Answering {@code false} here means an unpackaged, unentitled
     * module reads as ABSENT, which is the accurate answer for every customer
     * who has never had it.
     */
    private boolean hasEverBeenEntitled(String moduleId) {
        return false;
    }

    // ---- Application-facing state ----

    public SubscriptionState subscriptionState() {
        return subscriptionState;
    }

    /** True when accounting data may be created, edited or posted. */
    public boolean allowsMutation() {
        return subscriptionState.allowsMutation();
    }

    /** True when data may be read and exported. True in every state but UNLICENSED. */
    public boolean allowsRead() {
        return subscriptionState.allowsRead();
    }

    public String plan() {
        return plan;
    }

    public Set<String> entitledModules() {
        return entitledModules;
    }

    public LocalDate subscriptionEnd() {
        return subscriptionEnd;
    }

    /** Negative once the subscription has ended. */
    public long daysUntilExpiry() {
        return daysUntilExpiry;
    }

    /** Why the entitlement is unusable, when it is. */
    public java.util.Optional<String> invalidReason() {
        return java.util.Optional.ofNullable(invalidReason);
    }

    /** True while the renewal warning should be shown. */
    public boolean shouldWarnAboutRenewal() {
        return subscriptionState == SubscriptionState.RENEWAL_WINDOW;
    }
}
