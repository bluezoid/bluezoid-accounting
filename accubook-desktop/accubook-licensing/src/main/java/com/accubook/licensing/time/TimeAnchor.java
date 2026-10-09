package com.accubook.licensing.time;

import java.time.Instant;

/**
 * A clock that cannot be moved backwards.
 *
 * <p>Subscription expiry is evaluated against
 * {@code max(systemNow, lastKnownGoodTime)}, where the anchor only ever
 * advances. Setting the system clock back therefore cannot extend a
 * subscription: the anchor remembers the furthest point already reached.
 *
 * <p>Moving the clock <em>forward</em> is not defended against, deliberately -
 * it only shortens the customer's own subscription, which nobody does on
 * purpose, and tolerating it avoids false alarms from timezone changes, DST and
 * ordinary NTP correction.
 *
 * <p>The anchor is advisory, not a security boundary. Someone who can patch the
 * binary can defeat it; someone who merely changes the system clock cannot. That
 * is the distinction the threat model draws.
 */
public final class TimeAnchor {

    /**
     * Tolerated backward movement before it is treated as tampering.
     *
     * <p>An hour absorbs DST transitions, timezone changes and NTP corrections,
     * none of which should raise a warning.
     */
    public static final long TOLERATED_BACKWARD_DRIFT_MS = 60 * 60 * 1000L;

    private Instant highWaterMark;
    private boolean backwardJumpObserved;

    public TimeAnchor(Instant initialHighWaterMark) {
        this.highWaterMark = initialHighWaterMark == null ? Instant.EPOCH : initialHighWaterMark;
    }

    /**
     * Fold an observation of the system clock into the anchor.
     *
     * @return the effective time to evaluate expiry against
     */
    public Instant observe(Instant systemNow) {
        if (systemNow.isAfter(highWaterMark)) {
            highWaterMark = systemNow;
            return systemNow;
        }
        if (highWaterMark.toEpochMilli() - systemNow.toEpochMilli() > TOLERATED_BACKWARD_DRIFT_MS) {
            backwardJumpObserved = true;
        }
        // The clock reads earlier than a time already seen, so expiry is
        // evaluated against the furthest point reached.
        return highWaterMark;
    }

    /** The effective time without recording a new observation. */
    public Instant effectiveNow(Instant systemNow) {
        return systemNow.isAfter(highWaterMark) ? systemNow : highWaterMark;
    }

    /** The furthest point in time this installation has seen. */
    public Instant highWaterMark() {
        return highWaterMark;
    }

    /** True when the clock has been moved back beyond the tolerated drift. */
    public boolean backwardJumpObserved() {
        return backwardJumpObserved;
    }
}
