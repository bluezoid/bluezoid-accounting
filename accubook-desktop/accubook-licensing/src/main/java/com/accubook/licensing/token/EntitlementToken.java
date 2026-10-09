package com.accubook.licensing.token;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * The signed statement of what a customer is entitled to.
 *
 * <p>Issued by the licence server in exchange for a valid licence key and a
 * machine fingerprint. This is the authority for everything the application
 * permits - the licence key itself grants nothing.
 *
 * <h2>Why the dates are {@link LocalDate}</h2>
 * A subscription runs to the end of a calendar day, not to an instant. Storing
 * an instant would make expiry depend on the customer's timezone, so a
 * subscription bought in Mumbai would end at a different moment than one bought
 * in Delhi. The reference application makes exactly this mistake with fiscal
 * years and its reports disagree as a result.
 *
 * @param licenceId          server-side identity of the licence
 * @param customerId         server-side identity of the customer
 * @param plan               BASIC / PROFESSIONAL / ENTERPRISE / CUSTOM
 * @param modules            module ids the customer may mutate
 * @param subscriptionStart  first day of cover, inclusive
 * @param subscriptionEnd    last day of cover, inclusive
 * @param renewalWindowDays  days before the end date when warnings begin
 * @param machineFingerprint hashed device signals this token is bound to
 * @param suspended          set by an administrator; read-only while true
 * @param issuedAt           when the server signed it, for replay protection
 * @param tokenId            unique per issue, for replay protection
 */
public record EntitlementToken(
        String licenceId,
        String customerId,
        String plan,
        Set<String> modules,
        LocalDate subscriptionStart,
        LocalDate subscriptionEnd,
        int renewalWindowDays,
        List<String> machineFingerprint,
        boolean suspended,
        Instant issuedAt,
        String tokenId) {

    /**
     * Days before {@link #subscriptionEnd} when the renewal warning begins.
     *
     * <p>Decision P3-1. This window sits <strong>before</strong> the end date:
     * the subscription is still active throughout and the application is fully
     * functional. It is a warning period, not extra time. At the end date the
     * application becomes read-only immediately.
     */
    public static final int DEFAULT_RENEWAL_WINDOW_DAYS = 14;

    public EntitlementToken {
        modules = Set.copyOf(modules);
        machineFingerprint = List.copyOf(machineFingerprint);
        if (subscriptionEnd.isBefore(subscriptionStart)) {
            throw new IllegalArgumentException(
                    "Subscription ends (" + subscriptionEnd + ") before it starts (" + subscriptionStart + ")");
        }
        if (renewalWindowDays < 0) {
            throw new IllegalArgumentException("Renewal window cannot be negative");
        }
    }

    /** First day on which the renewal warning is shown. */
    public LocalDate renewalWindowStart() {
        return subscriptionEnd.minusDays(renewalWindowDays);
    }

    /** True when {@code date} is on or before the last day of cover. */
    public boolean coversDate(LocalDate date) {
        return !date.isBefore(subscriptionStart) && !date.isAfter(subscriptionEnd);
    }

    /** True when the token grants mutation rights over {@code moduleId}. */
    public boolean includesModule(String moduleId) {
        return modules.contains(moduleId);
    }
}
