package com.accubook.persistence.migration;

/**
 * One forward-only schema step.
 *
 * <p>Migrations are hand-authored SQL, never generated diffs. SQLite cannot
 * {@code ALTER COLUMN}, add a foreign key to an existing table, or change
 * nullability in place - those need the twelve-step table rebuild, which a
 * generator will not produce correctly.
 *
 * <p>Once shipped, a migration is immutable. Editing one would leave databases
 * that already ran it permanently inconsistent with databases that ran the
 * edited version.
 */
public record Migration(int version, String description, String sql) {

    public Migration {
        if (version <= 0) {
            throw new IllegalArgumentException("Migration version must be positive: " + version);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Migration " + version + " needs a description");
        }
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Migration " + version + " has no SQL");
        }
    }
}
