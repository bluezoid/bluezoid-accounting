package com.accubook.persistence.migration;

/** A migration could not be applied. Carries the version that failed. */
public class MigrationException extends RuntimeException {

    private final int version;

    public MigrationException(int version, String message, Throwable cause) {
        super(message, cause);
        this.version = version;
    }

    public MigrationException(int version, String message) {
        super(message);
        this.version = version;
    }

    /** Schema version being applied when the failure occurred; 0 if not applicable. */
    public int version() {
        return version;
    }
}
