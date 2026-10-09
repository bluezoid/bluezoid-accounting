package com.accubook.licensing.time;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Clock-tamper resistance.
 *
 * <p>Setting the system clock back must not extend a subscription. Ordinary
 * clock corrections must not raise false alarms.
 */
@DisplayName("Time anchor")
class TimeAnchorTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    @DisplayName("advances with the clock")
    void advancesWithClock() {
        TimeAnchor anchor = new TimeAnchor(T0);
        Instant later = T0.plusSeconds(3600);
        assertThat(anchor.observe(later)).isEqualTo(later);
        assertThat(anchor.highWaterMark()).isEqualTo(later);
    }

    @Test
    @DisplayName("a backward clock change cannot rewind effective time")
    void backwardClockDoesNotRewind() {
        TimeAnchor anchor = new TimeAnchor(T0);
        anchor.observe(T0.plusSeconds(86_400));

        Instant rolledBack = T0.minusSeconds(365L * 86_400);
        assertThat(anchor.observe(rolledBack))
                .as("expiry is evaluated against the furthest point already seen")
                .isEqualTo(T0.plusSeconds(86_400));
    }

    @Test
    @DisplayName("a year-long rollback is flagged as tampering")
    void largeRollbackIsFlagged() {
        TimeAnchor anchor = new TimeAnchor(T0);
        anchor.observe(T0);
        anchor.observe(T0.minusSeconds(365L * 86_400));
        assertThat(anchor.backwardJumpObserved()).isTrue();
    }

    @Test
    @DisplayName("a DST or NTP correction is tolerated silently")
    void smallDriftIsTolerated() {
        // An hour covers DST, timezone changes and ordinary NTP correction.
        // Treating those as tampering would cry wolf twice a year.
        TimeAnchor anchor = new TimeAnchor(T0);
        anchor.observe(T0);
        anchor.observe(T0.minusSeconds(1800));
        assertThat(anchor.backwardJumpObserved()).isFalse();
    }

    @Test
    @DisplayName("effectiveNow does not record an observation")
    void effectiveNowIsPure() {
        TimeAnchor anchor = new TimeAnchor(T0);
        Instant later = T0.plusSeconds(3600);
        assertThat(anchor.effectiveNow(later)).isEqualTo(later);
        assertThat(anchor.highWaterMark()).isEqualTo(T0);
    }

    @Test
    @DisplayName("forward jumps are accepted - they only shorten the customer's own cover")
    void forwardJumpAccepted() {
        TimeAnchor anchor = new TimeAnchor(T0);
        Instant farFuture = T0.plusSeconds(10L * 365 * 86_400);
        assertThat(anchor.observe(farFuture)).isEqualTo(farFuture);
        assertThat(anchor.backwardJumpObserved()).isFalse();
    }
}
