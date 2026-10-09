package com.accubook.licensing.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Machine binding: 3-of-5 matching (decision P3-2).
 *
 * <p>The design goal is not to defeat a determined attacker - the threat model
 * says plainly that someone who can patch the binary has already won. It is to
 * make casual copying ineffective <em>without</em> locking out a customer whose
 * machine changed in some ordinary way.
 */
@DisplayName("Machine fingerprint")
class MachineFingerprintTest {

    @Test
    @DisplayName("a fingerprint matches itself")
    void matchesItself(@TempDir Path dir) {
        MachineFingerprint fp = MachineFingerprint.collect(dir.resolve("install.id"));
        assertThat(fp.matches(fp)).isTrue();
        assertThat(fp.hashes()).hasSize(MachineFingerprint.TOTAL_SIGNALS);
    }

    @Test
    @DisplayName("the same machine matches across restarts")
    void stableAcrossRestarts(@TempDir Path dir) {
        Path installId = dir.resolve("install.id");
        MachineFingerprint first = MachineFingerprint.collect(installId);
        MachineFingerprint second = MachineFingerprint.collect(installId);
        assertThat(first.matches(second)).isTrue();
    }

    @Test
    @DisplayName("3 of 5 signals is a match")
    void threeOfFiveMatches() {
        MachineFingerprint stored = MachineFingerprint.of(List.of("a", "b", "c", "d", "e"));
        MachineFingerprint now = MachineFingerprint.of(List.of("a", "b", "c", "CHANGED", "CHANGED"));
        assertThat(now.matchCount(stored)).isEqualTo(3);
        assertThat(now.matches(stored)).isTrue();
    }

    @Test
    @DisplayName("2 of 5 is not a match")
    void twoOfFiveDoesNotMatch() {
        MachineFingerprint stored = MachineFingerprint.of(List.of("a", "b", "c", "d", "e"));
        MachineFingerprint now = MachineFingerprint.of(List.of("a", "b", "X", "Y", "Z"));
        assertThat(now.matchCount(stored)).isEqualTo(2);
        assertThat(now.matches(stored)).isFalse();
    }

    @Test
    @DisplayName("a different machine does not match")
    void differentMachineDoesNotMatch() {
        MachineFingerprint a = MachineFingerprint.of(List.of("a1", "a2", "a3", "a4", "a5"));
        MachineFingerprint b = MachineFingerprint.of(List.of("b1", "b2", "b3", "b4", "b5"));
        assertThat(a.matches(b)).isFalse();
    }

    @Test
    @DisplayName("installing Docker or a VPN does not lock the customer out")
    void networkChangeDoesNotLockOut() {
        // The scenario that motivated not enumerating every MAC: the customer
        // installs Docker, a new interface appears, and the MAC signal changes.
        // Four signals still agree, so they keep working.
        MachineFingerprint stored = MachineFingerprint.of(
                List.of("install", "machineid", "cpu", "MAC-BEFORE", "os"));
        MachineFingerprint afterDocker = MachineFingerprint.of(
                List.of("install", "machineid", "cpu", "MAC-AFTER", "os"));
        assertThat(afterDocker.matches(stored)).isTrue();
    }

    @Test
    @DisplayName("swapping a disk and a network card still matches")
    void hardwareChangeStillMatches() {
        MachineFingerprint stored = MachineFingerprint.of(
                List.of("install", "machineid", "cpu", "mac", "os"));
        MachineFingerprint repaired = MachineFingerprint.of(
                List.of("install", "machineid", "cpu", "NEW-MAC", "NEW-OS-VERSION"));
        assertThat(repaired.matches(stored))
                .as("three stable signals still agree")
                .isTrue();
    }

    @Test
    @DisplayName("an unavailable signal is not counted as agreement")
    void emptySignalIsNotAMatch() {
        // Two machines that both fail to read /etc/machine-id must not look
        // identical because of it.
        MachineFingerprint a = MachineFingerprint.of(List.of("", "", "", "", ""));
        MachineFingerprint b = MachineFingerprint.of(List.of("", "", "", "", ""));
        assertThat(a.matchCount(b)).isZero();
        assertThat(a.matches(b)).isFalse();
    }

    @Test
    @DisplayName("the install id persists once created")
    void installIdPersists(@TempDir Path dir) {
        Path file = dir.resolve("install.id");
        String first = MachineFingerprint.installUuid(file);
        String second = MachineFingerprint.installUuid(file);
        assertThat(first).isNotEmpty().isEqualTo(second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"docker0", "veth1234", "br-abc", "virbr0", "vmnet1",
            "vboxnet0", "tun0", "tap0", "utun3", "wg0"})
    @DisplayName("known virtual interfaces are excluded")
    void excludesVirtualInterfaces(String name) {
        // NetworkInterface.isVirtual() cannot be trusted - it reports docker0 as
        // false - so the names are matched explicitly.
        assertThat(MachineFingerprint.isKnownVirtual(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eth0", "wlan0", "enp3s0", "wlp3s0", "en0"})
    @DisplayName("real interfaces are not excluded")
    void keepsRealInterfaces(String name) {
        assertThat(MachineFingerprint.isKnownVirtual(name)).isFalse();
    }

    @Test
    @DisplayName("only hashes are stored, never raw values")
    void storesOnlyHashes(@TempDir Path dir) {
        // A MAC address is personal data; there is no reason to hold an
        // inventory of customers' hardware.
        MachineFingerprint fp = MachineFingerprint.collect(dir.resolve("install.id"));
        assertThat(fp.hashes())
                .filteredOn(h -> !h.isEmpty())
                .allSatisfy(h -> assertThat(h).hasSize(64).matches("[0-9a-f]+"));
    }

    @Test
    @DisplayName("does not require privileged hardware identifiers")
    void worksWithoutRoot(@TempDir Path dir) {
        // Measured: /sys/class/dmi/id/product_uuid is not readable by a non-root
        // process. An accounting application must not require root.
        MachineFingerprint fp = MachineFingerprint.collect(dir.resolve("install.id"));
        assertThat(fp.hashes().stream().filter(h -> !h.isEmpty()).count())
                .as("enough signals are available to an ordinary user")
                .isGreaterThanOrEqualTo(MachineFingerprint.REQUIRED_MATCHES);
    }
}
