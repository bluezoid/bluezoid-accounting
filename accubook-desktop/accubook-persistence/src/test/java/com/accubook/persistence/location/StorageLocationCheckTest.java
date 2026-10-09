package com.accubook.persistence.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decision P2-3: unsafe storage locations are refused, not warned about.
 */
@DisplayName("Storage location check")
class StorageLocationCheckTest {

    @Test
    @DisplayName("accepts an ordinary local directory")
    void acceptsLocalDirectory(@TempDir Path dir) {
        assertThat(StorageLocationCheck.isSupported(dir)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/home/user/Dropbox/AccuBook",
            "/home/user/OneDrive/books",
            "/Users/x/Google Drive/AccuBook",
            "/home/user/Nextcloud/accounts",
            "/home/user/pCloud/data",
            "/Users/x/Library/Mobile Documents/com~apple~CloudDocs/books"
    })
    @DisplayName("refuses a file-sync folder")
    void refusesSyncFolders(String path) {
        // The sync client copies company.db independently of its -wal and -shm
        // companions, so what lands in the cloud is internally inconsistent.
        assertThat(StorageLocationCheck.isSupported(Path.of(path))).isFalse();
        assertThat(StorageLocationCheck.inspect(Path.of(path)))
                .get()
                .extracting(StorageLocationCheck.Rejection::reason)
                .isEqualTo(StorageLocationCheck.Reason.SYNC_FOLDER);
    }

    @Test
    @DisplayName("refuses a Windows UNC path")
    void refusesUncPath() {
        assertThat(StorageLocationCheck.isSupported(Path.of("\\\\fileserver\\accounts\\AccuBook")))
                .isFalse();
    }

    @Test
    @DisplayName("does not mistake a company named after a sync product")
    void doesNotMatchSubstringsInsideNames() {
        // "Dropbox Traders Pvt Ltd" is a plausible customer name. Matching a
        // bare substring would refuse a perfectly good local directory.
        assertThat(StorageLocationCheck.isSupported(Path.of("/home/user/data/Dropbox Traders")))
                .isTrue();
    }

    @Test
    @DisplayName("the refusal explains what to do about it")
    void refusalIsActionable() {
        assertThatThrownBy(() ->
                StorageLocationCheck.requireSupported(Path.of("/home/user/Dropbox/AccuBook")))
                .isInstanceOf(UnsupportedStorageLocationException.class)
                .hasMessageContaining("file-sync folder")
                .hasMessageContaining("Move the company data to a local disk");
    }

    @Test
    @DisplayName("checks a path that does not exist yet")
    void handlesNonExistentPath(@TempDir Path dir) {
        // The user picks a location before anything is created there, so the
        // check must work on a path with no directory behind it.
        Path notYetCreated = dir.resolve("a").resolve("b").resolve("c");
        assertThat(StorageLocationCheck.isSupported(notYetCreated)).isTrue();
    }

    @Test
    @DisplayName("the exception carries the path and the reason")
    void exceptionCarriesContext() {
        Path path = Path.of("/home/user/OneDrive/books");
        try {
            StorageLocationCheck.requireSupported(path);
        } catch (UnsupportedStorageLocationException e) {
            assertThat(e.path()).isEqualTo(path);
            assertThat(e.reason()).isEqualTo(StorageLocationCheck.Reason.SYNC_FOLDER);
        }
    }
}
