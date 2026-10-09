package com.accubook.persistence.guard;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the target table from a mutating SQL statement.
 *
 * <p>Used by the persistence guard to answer "what is this statement about to
 * change?" without a full SQL parser.
 *
 * <h2>Why this is safe despite being simple</h2>
 * The guard is a <em>second</em> layer. Its job is to catch a mutation that
 * slipped past the use-case interceptor, not to be the sole defence. Statements
 * here are written by us, not by users, so they are ordinary
 * {@code INSERT INTO x}, {@code UPDATE x SET}, {@code DELETE FROM x} forms.
 *
 * <p>Critically, the failure mode is chosen deliberately: a statement whose table
 * cannot be identified is treated as <strong>mutating an unknown table</strong>
 * and is refused when a restriction is in force, rather than being waved through.
 * A guard that fails open is not a guard.
 */
public final class GuardedStatements {

    private static final Pattern INSERT =
            Pattern.compile("^\\s*INSERT\\s+(?:OR\\s+\\w+\\s+)?INTO\\s+[\"'`\\[]?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE =
            Pattern.compile("^\\s*UPDATE\\s+[\"'`\\[]?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE =
            Pattern.compile("^\\s*DELETE\\s+FROM\\s+[\"'`\\[]?(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REPLACE =
            Pattern.compile("^\\s*REPLACE\\s+INTO\\s+[\"'`\\[]?(\\w+)", Pattern.CASE_INSENSITIVE);

    /** DDL and pragmas are schema operations, governed by migrations rather than entitlement. */
    private static final Pattern SCHEMA_OPERATION =
            Pattern.compile("^\\s*(CREATE|ALTER|DROP|PRAGMA|VACUUM|BEGIN|COMMIT|ROLLBACK|ANALYZE|REINDEX)\\b",
                    Pattern.CASE_INSENSITIVE);

    private GuardedStatements() {
    }

    /** True when the statement changes data and therefore needs authorising. */
    public static boolean isMutating(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        String trimmed = sql.strip();
        if (SCHEMA_OPERATION.matcher(trimmed).find()) {
            return false;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        return upper.startsWith("INSERT") || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE") || upper.startsWith("REPLACE");
    }

    /**
     * The table a mutating statement targets.
     *
     * @return the table, or empty when it cannot be determined - which callers
     *         must treat as a refusal, not as permission
     */
    public static Optional<String> targetTable(String sql) {
        if (sql == null) {
            return Optional.empty();
        }
        for (Pattern pattern : new Pattern[]{INSERT, UPDATE, DELETE, REPLACE}) {
            Matcher matcher = pattern.matcher(sql);
            if (matcher.find()) {
                return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }
}
