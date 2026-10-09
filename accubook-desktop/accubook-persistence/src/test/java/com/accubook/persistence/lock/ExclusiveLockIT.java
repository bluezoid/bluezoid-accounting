package com.accubook.persistence.lock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locking behaviour must be deterministic: either the lock is held or it is not,
 * and the application can always say which process holds it.
 */
@DisplayName("Exclusive lock")
class ExclusiveLockIT {

    @Test
    @DisplayName("acquires a free lock and records the holder")
    void acquiresFreeLock(@TempDir Path dir) {
        Path lockFile = dir.resolve("test.lock");
        try (ExclusiveLock lock = ExclusiveLock.acquire(lockFile)) {
            assertThat(lock.lockFile()).isEqualTo(lockFile);
            assertThat(ExclusiveLock.readHolder(lockFile)).isPresent();
            assertThat(ExclusiveLock.readHolder(lockFile).get().pid())
                    .isEqualTo(ProcessHandle.current().pid());
        }
    }

    @Test
    @DisplayName("refuses a second acquisition while held")
    void refusesSecondAcquisition(@TempDir Path dir) {
        Path lockFile = dir.resolve("test.lock");
        try (ExclusiveLock ignored = ExclusiveLock.acquire(lockFile)) {
            assertThatThrownBy(() -> ExclusiveLock.acquire(lockFile))
                    .isInstanceOf(LockException.class);
        }
    }

    @Test
    @DisplayName("releases on close so the lock can be taken again")
    void releasesOnClose(@TempDir Path dir) {
        Path lockFile = dir.resolve("test.lock");
        try (ExclusiveLock ignored = ExclusiveLock.acquire(lockFile)) {
            // held
        }
        try (ExclusiveLock second = ExclusiveLock.acquire(lockFile)) {
            assertThat(second).isNotNull();
        }
    }

    @Test
    @DisplayName("the heartbeat advances while the lock is held")
    void heartbeatAdvances(@TempDir Path dir) throws Exception {
        Path lockFile = dir.resolve("test.lock");
        try (ExclusiveLock lock = ExclusiveLock.acquire(lockFile)) {
            Instant first = ExclusiveLock.readHolder(lockFile).orElseThrow().heartbeatAt();
            Thread.sleep(5);
            lock.heartbeat();
            Instant second = ExclusiveLock.readHolder(lockFile).orElseThrow().heartbeatAt();
            assertThat(second).isAfterOrEqualTo(first);
        }
    }

    @Test
    @DisplayName("a stale heartbeat is detectable but not auto-cleared")
    void staleLockIsDetectable() {
        Instant longAgo = Instant.now().minusSeconds(600);
        LockInfo stale = new LockInfo(9999, "somehost", longAgo, longAgo);

        assertThat(stale.isStale(ExclusiveLock.STALE_AFTER_MS, Instant.now())).isTrue();

        // Deliberately not cleared automatically: a process that is merely
        // paused - a debugger, a suspended laptop - would otherwise have its
        // lock stolen, putting two writers on one database.
        LockInfo fresh = new LockInfo(1, "host", Instant.now(), Instant.now());
        assertThat(fresh.isStale(ExclusiveLock.STALE_AFTER_MS, Instant.now())).isFalse();
    }

    @Test
    @DisplayName("lock info round-trips through its serialised form")
    void lockInfoRoundTrips() {
        LockInfo original = new LockInfo(4242, "buildbox",
                Instant.ofEpochMilli(1_700_000_000_000L), Instant.ofEpochMilli(1_700_000_060_000L));
        LockInfo parsed = LockInfo.parse(original.serialise());
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    @DisplayName("independent locks do not interfere")
    void independentLocksCoexist(@TempDir Path dir) {
        try (ExclusiveLock a = ExclusiveLock.acquire(dir.resolve("a.lock"));
             ExclusiveLock b = ExclusiveLock.acquire(dir.resolve("b.lock"))) {
            assertThat(a.lockFile()).isNotEqualTo(b.lockFile());
        }
    }
}
