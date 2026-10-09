package com.accubook.persistence.migration;

/**
 * The database was written by a newer version of AccuBook than this one.
 *
 * <p>Opening it anyway is the single most destructive thing an accounting
 * application can do: older code does not know about newer columns or
 * constraints, so it writes rows that quietly violate them. The file is refused
 * instead, and the user is told to update.
 *
 * <p>This is a realistic case, not a defensive one - a customer restores a
 * company file from a machine that updated earlier, or opens a backup taken
 * after an update.
 */
public class SchemaNewerThanApplicationException extends MigrationException {

    private final int fileVersion;
    private final int applicationVersion;

    public SchemaNewerThanApplicationException(int fileVersion, int applicationVersion) {
        super(fileVersion,
                "This database was created by a newer version of AccuBook (schema v"
                        + fileVersion + "; this version supports v" + applicationVersion
                        + "). Update AccuBook to open it. The file has not been modified.");
        this.fileVersion = fileVersion;
        this.applicationVersion = applicationVersion;
    }

    public int fileVersion() {
        return fileVersion;
    }

    public int applicationVersion() {
        return applicationVersion;
    }
}
