package com.accubook.persistence.lock;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * An exclusive lock over one resource, held for the life of the process.
 *
 * <p>Used twice: once per installation, so a second copy of the application
 * cannot start, and once per company database, so two processes cannot open the
 * same books.
 *
 * <h2>Why an application-level lock when SQLite already locks</h2>
 * SQLite's own locking prevents file corruption, and that is all it prevents.
 * It does not stop two processes each holding their own idea of the current
 * company, their own caches, and their own view of what has been posted. On a
 * desktop this is a realistic scenario - a user double-clicks the icon twice, or
 * an installer relaunches the app while the old one is still shutting down.
 *
 * <h2>Two mechanisms, deliberately</h2>
 * The OS {@link FileLock} is the authority: the kernel releases it even when the
 * process is killed, so it can never leak. The text content beside it is
 * advisory - it exists so that when a lock *is* held, the application can say
 * which process holds it rather than "try again later".
 */
public final class ExclusiveLock implements AutoCloseable {

    /** A heartbeat older than this suggests the holder died rather than paused. */
    public static final long STALE_AFTER_MS = 60_000;

    private final Path lockFile;
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final FileLock lock;

    private ExclusiveLock(Path lockFile, RandomAccessFile file, FileChannel channel, FileLock lock) {
        this.lockFile = lockFile;
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquire the lock, or fail.
     *
     * @throws LockException if another process holds it
     */
    public static ExclusiveLock acquire(Path lockFile) {
        try {
            Files.createDirectories(lockFile.getParent());
        } catch (IOException e) {
            throw new LockException("Cannot create lock directory for " + lockFile, e);
        }

        RandomAccessFile file = null;
        FileChannel channel = null;
        try {
            file = new RandomAccessFile(lockFile.toFile(), "rw");
            channel = file.getChannel();

            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                // Same JVM already holds it. Treated as contention, not a bug,
                // because the caller's recovery is identical.
                closeQuietly(channel, file);
                throw new LockException(
                        "This application already holds a lock on " + lockFile, readHolder(lockFile).orElse(null));
            }

            if (lock == null) {
                LockInfo holder = readHolder(lockFile).orElse(null);
                closeQuietly(channel, file);
                throw new LockException(describeContention(lockFile, holder), holder);
            }

            writeHolder(file, currentProcessInfo());
            return new ExclusiveLock(lockFile, file, channel, lock);

        } catch (IOException e) {
            closeQuietly(channel, file);
            throw new LockException("Cannot acquire lock on " + lockFile, e);
        }
    }

    /**
     * Refresh the heartbeat, so a live holder is distinguishable from a crashed
     * one. Callers invoke this periodically while the lock is held.
     */
    public void heartbeat() {
        try {
            LockInfo current = currentProcessInfo();
            writeHolder(file, current);
        } catch (IOException e) {
            // A failed heartbeat does not invalidate the lock - the OS lock is
            // still held - so this is not worth failing an operation over.
        }
    }

    /** Read the recorded holder without acquiring anything. */
    public static Optional<LockInfo> readHolder(Path lockFile) {
        try {
            if (!Files.exists(lockFile)) {
                return Optional.empty();
            }
            String content = Files.readString(lockFile, StandardCharsets.UTF_8).strip();
            return content.isEmpty() ? Optional.empty() : Optional.of(LockInfo.parse(content));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public Path lockFile() {
        return lockFile;
    }

    private static String describeContention(Path lockFile, LockInfo holder) {
        if (holder == null) {
            return "Another process is using " + lockFile + ".";
        }
        boolean stale = holder.isStale(STALE_AFTER_MS, Instant.now());
        return "Already in use by process " + holder.pid() + " on " + holder.host()
                + " (since " + holder.acquiredAt() + ")."
                + (stale
                ? " That process has not responded since " + holder.heartbeatAt()
                + ", so it may have stopped unexpectedly."
                : "");
    }

    private static LockInfo currentProcessInfo() {
        Instant now = Instant.now();
        return new LockInfo(ProcessHandle.current().pid(), hostName(), now, now);
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private static void writeHolder(RandomAccessFile file, LockInfo info) throws IOException {
        byte[] bytes = info.serialise().getBytes(StandardCharsets.UTF_8);
        file.setLength(0);
        file.seek(0);
        file.write(bytes);
        file.getFD().sync();
    }

    private static void closeQuietly(FileChannel channel, RandomAccessFile file) {
        try {
            if (channel != null) channel.close();
        } catch (IOException ignored) {
            // Already failing; the original cause is what matters.
        }
        try {
            if (file != null) file.close();
        } catch (IOException ignored) {
            // As above.
        }
    }

    @Override
    public void close() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // The OS releases the lock when the channel closes regardless.
        }
        closeQuietly(channel, file);
        try {
            // Remove the advisory content so a later reader does not report a
            // holder that has gone. Failure is harmless: the OS lock is the
            // authority, and a stale file is detected by its heartbeat.
            Files.deleteIfExists(lockFile);
        } catch (IOException ignored) {
            // As above.
        }
    }
}
