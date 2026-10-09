package com.accubook.persistence.location;

import java.nio.file.Path;

/**
 * A company database sits somewhere SQLite cannot be used safely.
 *
 * <p>Decision P2-3: this is a refusal, not a warning. WAL mode requires shared
 * memory and cannot work across a network filesystem at all, and file-sync
 * clients copy the {@code .db} independently of its {@code -wal}/{@code -shm}
 * sidecars, producing a torn database that no recovery can reconstruct. Both are
 * guaranteed corruption of statutory records rather than a risk to be accepted,
 * so the application declines to open the file.
 */
public class UnsupportedStorageLocationException extends RuntimeException {

    private final Path path;
    private final StorageLocationCheck.Reason reason;

    public UnsupportedStorageLocationException(Path path, StorageLocationCheck.Reason reason, String detail) {
        super(buildMessage(path, reason, detail));
        this.path = path;
        this.reason = reason;
    }

    private static String buildMessage(Path path, StorageLocationCheck.Reason reason, String detail) {
        String what = switch (reason) {
            case NETWORK_FILESYSTEM ->
                    "a network drive or shared folder (" + detail + ")";
            case SYNC_FOLDER ->
                    "a file-sync folder (" + detail + ")";
            case READ_ONLY_FILESYSTEM ->
                    "a read-only filesystem";
            case UNREADABLE ->
                    "a location that cannot be inspected (" + detail + ")";
        };
        String why = switch (reason) {
            case NETWORK_FILESYSTEM, UNREADABLE ->
                    "SQLite cannot lock a database safely there, and its write-ahead log "
                            + "needs shared memory that network filesystems do not provide.";
            case SYNC_FOLDER ->
                    "Sync clients copy the database file separately from its -wal and -shm "
                            + "companions, which produces an unrecoverable half-written database.";
            case READ_ONLY_FILESYSTEM ->
                    "The application must be able to write to its own data directory.";
        };
        return "Refusing to use " + path + ": it is on " + what + ". " + why
                + " Move the company data to a local disk and try again.";
    }

    public Path path() {
        return path;
    }

    public StorageLocationCheck.Reason reason() {
        return reason;
    }
}
