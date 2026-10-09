package com.accubook.persistence.migration;

import java.nio.file.Path;
import java.util.Optional;

/** Outcome of a migration attempt. */
public record MigrationResult(int fromVersion, int toVersion, Path backupPath, boolean changed) {

    static MigrationResult upToDate(int version) {
        return new MigrationResult(version, version, null, false);
    }

    static MigrationResult migrated(int from, int to, Path backup) {
        return new MigrationResult(from, to, backup, true);
    }

    /** Where the pre-migration snapshot was written, if one was taken. */
    public Optional<Path> backup() {
        return Optional.ofNullable(backupPath);
    }
}
