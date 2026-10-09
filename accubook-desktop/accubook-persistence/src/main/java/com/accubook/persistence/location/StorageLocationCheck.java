package com.accubook.persistence.location;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a directory is a safe place to keep a SQLite accounting
 * database.
 *
 * <p>Implements decision P2-3. Two location classes are refused outright:
 *
 * <ul>
 *   <li><strong>Network filesystems.</strong> WAL mode needs shared memory,
 *       which NFS, SMB/CIFS and friends do not provide across hosts, and their
 *       advisory locking is historically unreliable. SQLite's own documentation
 *       warns against it.</li>
 *   <li><strong>File-sync folders</strong> (Dropbox, OneDrive, Google Drive,
 *       iCloud, and similar). The sync client copies {@code company.db}
 *       independently of {@code company.db-wal} and {@code company.db-shm}, so
 *       the uploaded set is internally inconsistent. Restoring it yields a
 *       database that cannot be repaired.</li>
 * </ul>
 *
 * <p>Accountants genuinely do put data on a shared drive so "everyone can see
 * it", and in a cloud folder so "it is backed up". Both feel sensible and both
 * destroy the books, so the check is a refusal rather than a warning.
 */
public final class StorageLocationCheck {

    /** Why a location was rejected. */
    public enum Reason {
        NETWORK_FILESYSTEM,
        SYNC_FOLDER,
        READ_ONLY_FILESYSTEM,
        UNREADABLE
    }

    /**
     * Filesystem types that are not local disks.
     *
     * <p>Matched case-insensitively against {@link FileStore#type()}. On Linux
     * this is the mount type; on macOS and Windows the JDK reports the driver
     * name, so both naming styles are listed.
     */
    private static final Set<String> NETWORK_FILESYSTEM_TYPES = Set.of(
            // POSIX mount types
            "nfs", "nfs3", "nfs4", "cifs", "smbfs", "smb", "smb2", "smb3",
            "afp", "afpfs", "webdav", "davfs", "davfs2", "sshfs", "fuse.sshfs",
            "ftpfs", "curlftpfs", "ncpfs", "coda", "afs", "9p", "glusterfs",
            "ceph", "cephfs", "lustre", "beegfs", "ocfs2", "gfs2",
            // Windows / macOS driver names
            "networkfs", "webdavfs", "nfsv3", "nfsv4"
    );

    /**
     * Path fragments that identify a sync client's managed folder.
     *
     * <p>Matched case-insensitively against the absolute path. Deliberately
     * simple: a false positive costs the customer one clear error message and a
     * move, whereas a false negative costs them their books.
     */
    private static final List<String> SYNC_FOLDER_MARKERS = List.of(
            "dropbox",
            "onedrive",
            "google drive",
            "googledrive",
            "gdrive",
            "icloud drive",
            "mobile documents/com~apple~clouddocs",
            "nextcloud",
            "owncloud",
            "pcloud",
            "box sync",
            "boxdrive",
            "sync.com",
            "mega",
            "yandex.disk",
            "tresorit",
            "sugarsync"
    );

    private StorageLocationCheck() {
    }

    /**
     * Refuse the location if it is unsafe.
     *
     * @throws UnsupportedStorageLocationException if the directory is on a
     *                                             network filesystem, inside a
     *                                             sync folder, or not writable
     */
    public static void requireSupported(Path directory) {
        inspect(directory).ifPresent(rejection -> {
            throw new UnsupportedStorageLocationException(
                    directory, rejection.reason(), rejection.detail());
        });
    }

    /** Non-throwing form, for showing the user why a location was rejected. */
    public static boolean isSupported(Path directory) {
        return inspect(directory).isEmpty();
    }

    /** A rejection, or empty when the location is usable. */
    public static Optional<Rejection> inspect(Path directory) {
        Path absolute = directory.toAbsolutePath().normalize();

        // Path-based checks first: they work whether or not the directory
        // already exists, which matters when the user is choosing a location
        // before anything has been created there.
        Optional<String> syncMarker = matchSyncFolder(absolute);
        if (syncMarker.isPresent()) {
            return Optional.of(new Rejection(Reason.SYNC_FOLDER, syncMarker.get()));
        }

        // A Windows UNC path (\\server\share) is a network location regardless
        // of what the filesystem layer reports. Checked on the raw string
        // because Path normalisation collapses the leading separators on POSIX.
        String raw = directory.toString();
        if (raw.startsWith("\\\\") || raw.startsWith("//")) {
            return Optional.of(new Rejection(Reason.NETWORK_FILESYSTEM, "UNC path " + raw));
        }

        // Filesystem-type check needs an existing path, so walk up to the
        // nearest ancestor that exists.
        Path existing = nearestExistingAncestor(absolute);
        if (existing == null) {
            return Optional.empty();
        }

        try {
            FileStore store = Files.getFileStore(existing);
            String type = store.type() == null ? "" : store.type().toLowerCase(Locale.ROOT);
            if (NETWORK_FILESYSTEM_TYPES.contains(type)
                    || type.startsWith("fuse.sshfs")
                    || type.startsWith("nfs")
                    || type.startsWith("smb")
                    || type.startsWith("cifs")) {
                return Optional.of(new Rejection(Reason.NETWORK_FILESYSTEM, type));
            }
            if (store.isReadOnly()) {
                return Optional.of(new Rejection(Reason.READ_ONLY_FILESYSTEM, type));
            }
        } catch (IOException e) {
            // Cannot determine what this is. For a statutory record the safe
            // default is to decline rather than to assume a local disk.
            return Optional.of(new Rejection(Reason.UNREADABLE, e.getMessage()));
        }

        return Optional.empty();
    }

    private static Optional<String> matchSyncFolder(Path absolute) {
        String haystack = absolute.toString().toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String marker : SYNC_FOLDER_MARKERS) {
            // Bounded by separators so a company called "Dropbox Traders" in a
            // normal directory is not mistaken for a sync folder.
            if (haystack.contains("/" + marker + "/") || haystack.endsWith("/" + marker)) {
                return Optional.of(marker);
            }
        }
        return Optional.empty();
    }

    private static Path nearestExistingAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current;
    }

    /** A refused location and the detail to show the user. */
    public record Rejection(Reason reason, String detail) {
    }
}
