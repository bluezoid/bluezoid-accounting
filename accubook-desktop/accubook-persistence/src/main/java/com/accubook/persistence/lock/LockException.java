package com.accubook.persistence.lock;

/** A lock could not be acquired. Carries who holds it, when that can be determined. */
public class LockException extends RuntimeException {

    private final LockInfo holder;

    public LockException(String message, LockInfo holder) {
        super(message);
        this.holder = holder;
    }

    public LockException(String message, Throwable cause) {
        super(message, cause);
        this.holder = null;
    }

    /** The current holder, or {@code null} if the lock file was unreadable. */
    public LockInfo holder() {
        return holder;
    }
}
