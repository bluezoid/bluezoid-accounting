package com.accubook.licensing.state;

import com.accubook.licensing.Entitlement;
import com.accubook.licensing.support.Tokens;
import com.accubook.licensing.token.EntitlementToken;
import com.accubook.module.ModuleState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The subscription state machine, including the P3-1 clarification.
 *
 * <p>The 14-day window sits <strong>before</strong> the end date. It is a
 * renewal warning during which the application is fully functional, not extra
 * time after expiry. At the end date the application becomes read-only
 * immediately.
 */
@DisplayName("Subscription lifecycle")
class SubscriptionLifecycleTest {

    private static final Set<String> PACKAGED = Set.of("accounting", "inventory");
    private static final LocalDate START = LocalDate.of(2026, 4, 1);
    private static final LocalDate END = LocalDate.of(2027, 3, 31);

    private Entitlement on(LocalDate today) {
        return Entitlement.resolve(
                Tokens.covering(START, END, "accounting", "inventory"), PACKAGED, today);
    }

    @Test
    @DisplayName("mid-subscription is ACTIVE and writable")
    void midSubscriptionIsActive() {
        Entitlement e = on(LocalDate.of(2026, 9, 1));
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(e.allowsMutation()).isTrue();
        assertThat(e.shouldWarnAboutRenewal()).isFalse();
    }

    @Test
    @DisplayName("15 days before expiry is still ACTIVE - the window has not opened")
    void fifteenDaysBeforeIsActive() {
        assertThat(on(END.minusDays(15)).subscriptionState()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    @DisplayName("14 days before expiry enters RENEWAL_WINDOW and stays writable")
    void fourteenDaysBeforeIsRenewalWindow() {
        Entitlement e = on(END.minusDays(14));
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.RENEWAL_WINDOW);
        assertThat(e.allowsMutation())
                .as("the renewal window is a warning, not a loss of function")
                .isTrue();
        assertThat(e.shouldWarnAboutRenewal()).isTrue();
    }

    @Test
    @DisplayName("the last day of cover is still writable")
    void lastDayIsStillWritable() {
        Entitlement e = on(END);
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.RENEWAL_WINDOW);
        assertThat(e.allowsMutation()).isTrue();
    }

    @Test
    @DisplayName("the day after expiry is READ-ONLY immediately")
    void dayAfterExpiryIsReadOnly() {
        // The core requirement: expired means read-only, with no post-expiry
        // functional period.
        Entitlement e = on(END.plusDays(1));
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.EXPIRED);
        assertThat(e.allowsMutation()).isFalse();
        assertThat(e.allowsRead()).as("reading and exporting always remain").isTrue();
    }

    @Test
    @DisplayName("there is no functional grace after expiry")
    void noFunctionalGraceAfterExpiry() {
        // Explicitly pinned, because the original design was ambiguous here.
        for (int day = 1; day <= 20; day++) {
            Entitlement e = on(END.plusDays(day));
            assertThat(e.allowsMutation())
                    .as("day %d after expiry must not permit mutation", day)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a suspended subscription is read-only whatever the dates say")
    void suspendedIsReadOnly() {
        Entitlement e = Entitlement.resolve(
                Tokens.suspended(LocalDate.of(2026, 9, 1), "accounting"), PACKAGED,
                LocalDate.of(2026, 9, 1));
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.SUSPENDED);
        assertThat(e.allowsMutation()).isFalse();
        assertThat(e.allowsRead()).isTrue();
    }

    @Test
    @DisplayName("a not-yet-valid token is refused, not treated as active")
    void notYetValidIsRefused() {
        Entitlement e = on(START.minusDays(1));
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.INVALID);
        assertThat(e.allowsMutation()).isFalse();
    }

    @Test
    @DisplayName("an unlicensed installation permits nothing but activation")
    void unlicensedPermitsNothing() {
        Entitlement e = Entitlement.unlicensed(PACKAGED);
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.UNLICENSED);
        assertThat(e.allowsMutation()).isFalse();
        assertThat(e.allowsRead()).isFalse();
    }

    @Test
    @DisplayName("an invalid entitlement is read-only and explains itself")
    void invalidIsReadOnlyWithReason() {
        Entitlement e = Entitlement.invalid("The licence file is damaged.", PACKAGED);
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.INVALID);
        assertThat(e.allowsMutation()).isFalse();
        assertThat(e.allowsRead()).as("data stays readable and exportable").isTrue();
        assertThat(e.invalidReason()).contains("The licence file is damaged.");
    }

    @Test
    @DisplayName("renewal returns an expired subscription to ACTIVE")
    void renewalRestoresActive() {
        LocalDate today = END.plusDays(5);
        assertThat(on(today).allowsMutation()).isFalse();

        EntitlementToken renewed = Tokens.covering(START, END.plusYears(1), "accounting");
        Entitlement after = Entitlement.resolve(renewed, PACKAGED, today);
        assertThat(after.subscriptionState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(after.allowsMutation()).isTrue();
    }

    @Test
    @DisplayName("expiry turns entitled modules READ_ONLY, never ABSENT")
    void expiryMakesModulesReadOnly() {
        Entitlement e = on(END.plusDays(1));
        assertThat(e.moduleState("accounting")).isEqualTo(ModuleState.READ_ONLY);
        assertThat(e.moduleState("accounting").allowsRead())
                .as("history is never withheld")
                .isTrue();
    }

    @Test
    @DisplayName("days until expiry is reported for the warning banner")
    void reportsDaysUntilExpiry() {
        assertThat(on(END.minusDays(10)).daysUntilExpiry()).isEqualTo(10);
        assertThat(on(END.plusDays(3)).daysUntilExpiry()).isNegative();
    }
}
