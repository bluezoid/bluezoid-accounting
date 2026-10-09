package com.accubook.persistence.guard;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

    /** The same verbs anywhere in the text, for a mutation inside a {@code WITH} clause. */
    private static final Pattern EMBEDDED_TARGET = Pattern.compile(
            "\\b(?:INSERT\\s+(?:OR\\s+\\w+\\s+)?INTO|REPLACE\\s+INTO|UPDATE(?:\\s+OR\\s+\\w+)?|DELETE\\s+FROM)"
                    + "\\s+[\"'`\\[]?(\\w+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MUTATION_KEYWORD =
            Pattern.compile("\\b(INSERT|UPDATE|DELETE|REPLACE)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Verbs a guarded unit of work may issue. Anything else is refused - an
     * unrecognised verb is a statement the guard does not understand.
     */
    private static final Set<String> PERMITTED_VERBS = Set.of(
            "SELECT", "VALUES", "EXPLAIN", "WITH",
            "INSERT", "UPDATE", "DELETE", "REPLACE",
            "PRAGMA", "SAVEPOINT", "RELEASE", "ROLLBACK", "ANALYZE");

    /** Pragmas that only report. Any other pragma given a value is a setting change. */
    private static final Set<String> READ_PRAGMAS = Set.of(
            "table_info", "table_xinfo", "index_list", "index_info", "index_xinfo",
            "foreign_key_list", "foreign_key_check", "integrity_check", "quick_check");

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
        String trimmed = stripLeadingComments(sql);
        if (SCHEMA_OPERATION.matcher(trimmed).find()) {
            return false;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.startsWith("WITH")) {
            // A common table expression may end in INSERT, UPDATE or DELETE.
            return MUTATION_KEYWORD.matcher(trimmed).find();
        }
        return upper.startsWith("INSERT") || upper.startsWith("UPDATE")
                || upper.startsWith("DELETE") || upper.startsWith("REPLACE");
    }

    /**
     * Why a guarded unit of work may never run this statement, whatever the
     * entitlement says.
     *
     * <p>Schema changes belong to migrations, which run unguarded. Transaction
     * control belongs to the unit of work: a {@code COMMIT} issued mid-work
     * would split one posting into two transactions. {@code ATTACH} opens a
     * second database the guard knows nothing about, and a setting pragma such
     * as {@code query_only = 0} or {@code foreign_keys = OFF} changes the rules
     * the rest of the transaction runs under.
     *
     * @return the refusal reason, or empty when the statement may run
     */
    public static Optional<String> forbiddenReason(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.empty();
        }
        String trimmed = stripLeadingComments(sql);
        String upper = trimmed.toUpperCase(Locale.ROOT);
        String verb = upper.split("[\\s(;]", 2)[0];
        switch (verb) {
            case "CREATE", "ALTER", "DROP", "VACUUM", "REINDEX":
                return Optional.of("schema changes run only in migrations");
            case "ATTACH", "DETACH":
                return Optional.of("attaching another database would escape the guard");
            case "BEGIN", "COMMIT", "END":
                return Optional.of("transaction control belongs to the unit of work");
            case "ROLLBACK":
                // ROLLBACK TO a savepoint stays inside the unit of work.
                return upper.matches("ROLLBACK\\s+(TRANSACTION\\s+)?TO\\b.*")
                        ? Optional.empty()
                        : Optional.of("transaction control belongs to the unit of work");
            case "PRAGMA":
                return isSettingPragma(trimmed)
                        ? Optional.of("changing a database setting is not permitted mid-transaction")
                        : Optional.empty();
            default:
                return PERMITTED_VERBS.contains(verb)
                        ? Optional.empty()
                        : Optional.of("the guard does not recognise this statement");
        }
    }

    /**
     * Remove leading whitespace and SQL comments, so a comment cannot hide the
     * verb from the classifier. {@code /*x*&#47; INSERT INTO stock} is an insert.
     */
    static String stripLeadingComments(String sql) {
        String rest = sql.strip();
        while (true) {
            if (rest.startsWith("--")) {
                int end = rest.indexOf('\n');
                rest = end < 0 ? "" : rest.substring(end + 1).strip();
            } else if (rest.startsWith("/*")) {
                int end = rest.indexOf("*/");
                rest = end < 0 ? "" : rest.substring(end + 2).strip();
            } else {
                return rest;
            }
        }
    }

    private static boolean isSettingPragma(String trimmed) {
        String body = trimmed.substring("PRAGMA".length()).strip();
        int open = body.indexOf('(');
        int equals = body.indexOf('=');
        if (open < 0 && equals < 0) {
            return false;
        }
        int end = open < 0 ? equals : (equals < 0 ? open : Math.min(open, equals));
        String name = body.substring(0, end).strip().toLowerCase(Locale.ROOT);
        // Strip a schema prefix: PRAGMA main.table_info(x)
        name = name.substring(name.lastIndexOf('.') + 1);
        return equals >= 0 && (open < 0 || equals < open) || !READ_PRAGMAS.contains(name);
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
        String trimmed = stripLeadingComments(sql);
        for (Pattern pattern : new Pattern[]{INSERT, UPDATE, DELETE, REPLACE}) {
            Matcher matcher = pattern.matcher(trimmed);
            if (matcher.find()) {
                return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        if (trimmed.toUpperCase(Locale.ROOT).startsWith("WITH")) {
            // The mutation follows the CTE definitions. A CTE that writes two
            // tables is not something this codebase issues; the first found is
            // checked, and the statement must still parse as one write.
            Matcher matcher = EMBEDDED_TARGET.matcher(trimmed);
            if (matcher.find()) {
                return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }
}
