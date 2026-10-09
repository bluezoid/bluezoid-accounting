package com.accubook.licensing.support;

import com.accubook.licensing.token.EntitlementToken;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Builders for entitlement tokens in the shapes the tests need. */
public final class Tokens {

    public static final List<String> MACHINE = List.of("h1", "h2", "h3", "h4", "h5");

    private Tokens() {
    }

    /** A token covering {@code start} to {@code end}. */
    public static EntitlementToken covering(LocalDate start, LocalDate end, String... modules) {
        return new EntitlementToken(
                "lic-1", "cust-1", "PROFESSIONAL",
                Set.of(modules),
                start, end,
                EntitlementToken.DEFAULT_RENEWAL_WINDOW_DAYS,
                MACHINE,
                false,
                Instant.parse("2026-04-01T00:00:00Z"),
                "token-1");
    }

    /** A token whose subscription is current around {@code today}. */
    public static EntitlementToken activeAround(LocalDate today, String... modules) {
        return covering(today.minusMonths(6), today.plusMonths(6), modules);
    }

    public static EntitlementToken suspended(LocalDate today, String... modules) {
        EntitlementToken base = activeAround(today, modules);
        return new EntitlementToken(base.licenceId(), base.customerId(), base.plan(),
                base.modules(), base.subscriptionStart(), base.subscriptionEnd(),
                base.renewalWindowDays(), base.machineFingerprint(),
                true, base.issuedAt(), base.tokenId());
    }

    public static EntitlementToken boundTo(List<String> fingerprint, LocalDate today, String... modules) {
        EntitlementToken base = activeAround(today, modules);
        return new EntitlementToken(base.licenceId(), base.customerId(), base.plan(),
                base.modules(), base.subscriptionStart(), base.subscriptionEnd(),
                base.renewalWindowDays(), fingerprint,
                base.suspended(), base.issuedAt(), base.tokenId());
    }
}
