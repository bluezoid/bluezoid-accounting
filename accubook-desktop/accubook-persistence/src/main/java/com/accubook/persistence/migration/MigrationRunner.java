package com.accubook.persistence.migration;

import com.accubook.persistence.connection.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies schema migrations to one database file, safely.
 *
 * <p>Governing rule: <strong>an application update must never delete, overwrite
 * or corrupt customer accounting data.</strong> Every step below exists to
 * uphold that.
 *
 * <pre>
 * read schema_version
 *   equal to expected   -&gt; nothing to do
 *   newer than expected -&gt; REFUSE, do not open
 *   older than expected -&gt; backup -&gt; verify backup -&gt; migrate
 *                          -&gt; validate -&gt; commit
 *                          on any failure: restore, keep the failed copy
 * </pre>
 *
 * <p>Migration runs <em>on open</em>, per file, never as a global sweep at
 * startup. A company file may simply not be present when the application is
 * updated - it is on an external drive, or archived, or restored months later -
 * and it must still migrate correctly when it does appear.
 */
public final class MigrationRunner {

    private final List<Migration> migrations;
    private final Path backupDir;

    public MigrationRunner(List<Migration> migrations, Path backupDir) {
        List<Migration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(Migration::version));
        assertNoDuplicateVersions(sorted);
        this.migrations = List.copyOf(sorted);
        this.backupDir = backupDir;
    }

    /** Highest version this build knows how to produce. */
    public int targetVersion() {
        return migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
    }

    /** Current schema version of {@code database}; 0 for a fresh file. */
    public int currentVersion(Database database) throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Bring {@code database} up to {@link #targetVersion()}.
     *
     * @return the outcome, including where the pre-migration backup was written
     * @throws SchemaNewerThanApplicationException if the file is from a newer build
     * @throws MigrationException                  if migration failed (the database
     *                                             is restored to its prior state)
     */
    public MigrationResult migrate(Database database) throws SQLException {
        int from = currentVersion(database);
        int to = targetVersion();

        if (from > to) {
            throw new SchemaNewerThanApplicationException(from, to);
        }
        if (from == to) {
            return MigrationResult.upToDate(from);
        }

        // A fresh file has nothing worth preserving, so the backup step is
        // skipped - there is no customer data to lose.
        Path backup = (from == 0) ? null : backupBeforeMigration(database, from);

        try {
            applyPending(database, from);
            validate(database);
            return MigrationResult.migrated(from, to, backup);
        } catch (SQLException | RuntimeException failure) {
            if (backup != null) {
                restore(database, backup, failure);
            }
            throw new MigrationException(from,
                    "Migration from v" + from + " to v" + to + " failed"
                            + (backup != null
                            ? "; the database was restored from " + backup
                            : "; the database was not modified"),
                    failure);
        }
    }

    /**
     * Snapshot the database before migrating.
     *
     * <p>Uses {@code VACUUM INTO} rather than a file copy. A live WAL database
     * cannot be safely copied at the filesystem level: committed data may still
     * be in the {@code -wal} sidecar, so the copy can be stale or torn.
     */
    private Path backupBeforeMigration(Database database, int fromVersion) throws SQLException {
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new MigrationException(fromVersion, "Cannot create backup directory " + backupDir, e);
        }

        String name = database.file().getFileName().toString()
                + ".pre-v" + fromVersion + "." + Instant.now().toEpochMilli() + ".bak";
        Path backup = backupDir.resolve(name);

        database.checkpoint();
        // VACUUM INTO writes a new file, so it cannot run on a query_only
        // connection - and it must not run inside a transaction either
        // ("cannot VACUUM from within a transaction"). It therefore uses the
        // write connection directly rather than inTransaction().
        database.vacuumInto(backup);

        verifyBackup(backup, fromVersion);
        return backup;
    }

    /**
     * Confirm the snapshot is readable and structurally sound.
     *
     * <p>An unverified backup is not a backup. Checking it now means a failed
     * migration has something trustworthy to restore from.
     */
    private void verifyBackup(Path backup, int fromVersion) throws SQLException {
        if (!Files.exists(backup)) {
            throw new MigrationException(fromVersion, "Backup was not created at " + backup);
        }
        try (Database check = Database.open(backup)) {
            IntegrityChecks.requireIntact(check);
        }
    }

    private void applyPending(Database database, int fromVersion) throws SQLException {
        for (Migration migration : migrations) {
            if (migration.version() <= fromVersion) {
                continue;
            }
            // Each step is its own transaction, so a failure leaves the database
            // at the last successfully applied version rather than half-way
            // through one.
            database.inUnguardedTransaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : splitStatements(migration.sql())) {
                        statement.execute(sql);
                    }
                    statement.execute("PRAGMA user_version = " + migration.version());
                }
                return null;
            });
        }
    }

    /**
     * Post-migration validation.
     *
     * <p>Structural checks alone are not enough for accounting data: a migration
     * can leave a perfectly valid database whose books no longer balance - a
     * mis-scaled money column, a dropped entry. {@link IntegrityChecks} therefore
     * also asserts the accounting invariant when a ledger is present.
     */
    private void validate(Database database) throws SQLException {
        IntegrityChecks.requireIntact(database);
        IntegrityChecks.requireForeignKeysIntact(database);
        IntegrityChecks.requireBooksBalanced(database);
    }

    /**
     * Put the pre-migration snapshot back.
     *
     * <p>The failed database is kept alongside, not deleted: it is the only
     * evidence of what went wrong, and support will want it.
     */
    private void restore(Database database, Path backup, Exception cause) {
        Path live = database.file();
        try {
            database.close();
            Path failed = live.resolveSibling(live.getFileName() + ".failed-" + Instant.now().toEpochMilli());
            Files.move(live, failed, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(backup, live, StandardCopyOption.REPLACE_EXISTING);
            deleteWalSidecars(live);
        } catch (Exception restoreFailure) {
            cause.addSuppressed(restoreFailure);
        }
    }

    /**
     * Remove {@code -wal}/{@code -shm} left over from the replaced file.
     *
     * <p>They belong to the database that was just moved away; leaving them
     * beside the restored copy would let SQLite replay the wrong journal.
     */
    private void deleteWalSidecars(Path database) throws IOException {
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-wal"));
        Files.deleteIfExists(database.resolveSibling(database.getFileName() + "-shm"));
    }

    /**
     * Split a migration script into executable statements.
     *
     * <p>Splitting on a trailing semicolon alone is not sufficient, because a
     * {@code CREATE TRIGGER} body contains its own statements:
     *
     * <pre>
     * CREATE TRIGGER t BEFORE UPDATE ON x
     * BEGIN
     *     SELECT RAISE(ABORT, 'no');   &lt;- inner semicolon
     * END;                             &lt;- the real terminator
     * </pre>
     *
     * A naive split hands SQLite the fragment up to the inner semicolon and it
     * fails with "incomplete input". So a {@code BEGIN} opens a block that only
     * a line consisting of {@code END;} closes.
     *
     * <p>Still deliberately simple: migrations are hand-written and reviewed, so
     * they are formatted for this. A full SQL parser would be a lot of machinery
     * to support syntax we do not intend to use.
     */
    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBlock = false;

        for (String line : script.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');

            String withoutComment = stripTrailingComment(trimmed).strip();
            String upper = withoutComment.toUpperCase(java.util.Locale.ROOT);

            if (!inBlock && (upper.equals("BEGIN") || upper.endsWith(" BEGIN"))) {
                inBlock = true;
                continue;
            }
            if (inBlock) {
                if (upper.equals("END;") || upper.equals("END ;")) {
                    statements.add(current.toString().strip());
                    current.setLength(0);
                    inBlock = false;
                }
                continue;
            }
            if (withoutComment.endsWith(";")) {
                statements.add(current.toString().strip());
                current.setLength(0);
            }
        }

        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
        return statements;
    }

    /** Drop a trailing {@code -- comment} so it cannot hide the terminator. */
    private static String stripTrailingComment(String line) {
        int marker = line.indexOf("--");
        return marker < 0 ? line : line.substring(0, marker);
    }

    private static void assertNoDuplicateVersions(List<Migration> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).version() == sorted.get(i - 1).version()) {
                throw new IllegalArgumentException(
                        "Duplicate migration version " + sorted.get(i).version());
            }
        }
    }
}
