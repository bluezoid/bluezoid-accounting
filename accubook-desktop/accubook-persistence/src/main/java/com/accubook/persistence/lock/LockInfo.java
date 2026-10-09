package com.accubook.persistence.lock;

import java.time.Instant;

/**
 * Who holds a lock, recorded in the lock file.
 *
 * <p>Stored as readable text rather than a binary marker so a support engineer
 * can tell at a glance whether a lock belongs to a live process or a crash.
 */
public record LockInfo(long pid, String host, Instant acquiredAt, Instant heartbeatAt) {

    /** Serialised form: {@code pid|host|acquiredEpochMs|heartbeatEpochMs}. */
    public String serialise() {
        return pid + "|" + host + "|" + acquiredAt.toEpochMilli() + "|" + heartbeatAt.toEpochMilli();
    }

    public static LockInfo parse(String line) {
        String[] parts = line.strip().split("\\|");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Malformed lock file content: " + line);
        }
        return new LockInfo(
                Long.parseLong(parts[0]),
                parts[1],
                Instant.ofEpochMilli(Long.parseLong(parts[2])),
                Instant.ofEpochMilli(Long.parseLong(parts[3])));
    }

    /**
     * True when the heartbeat has not been refreshed within {@code staleAfterMs}.
     *
     * <p>A stale lock almost always means the process was killed. It is offered
     * for override rather than cleared automatically: silently stealing a lock
     * from a process that is merely paused (a debugger, a suspended laptop)
     * would put two writers on one database.
     */
    public boolean isStale(long staleAfterMs, Instant now) {
        return heartbeatAt.plusMillis(staleAfterMs).isBefore(now);
    }
}
