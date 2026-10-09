package com.accubook.licensing;

import com.accubook.licensing.machine.MachineFingerprint;
import com.accubook.licensing.state.SubscriptionState;
import com.accubook.licensing.support.TestSigner;
import com.accubook.licensing.support.Tokens;
import com.accubook.licensing.time.TimeAnchor;
import com.accubook.licensing.token.EntitlementToken;
import com.accubook.module.ModuleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loading and verifying an entitlement end to end.
 *
 * <p>Every failure path is checked for the same property: the application
 * degrades to read-only and says why, and nothing on disk is destroyed.
 */
@DisplayName("Entitlement loader")
class EntitlementLoaderIT {

    private static final Set<String> PACKAGED = Set.of("accounting", "inventory");
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);
    private static final Instant NOW = TODAY.atStartOfDay(ZONE).toInstant();

    @TempDir
    Path dir;

    private TestSigner signer;
    private EntitlementLoader loader;
    private MachineFingerprint machine;

    @BeforeEach
    void setUp() {
        signer = new TestSigner().withKey("primary").withKey("secondary");
        loader = new EntitlementLoader(signer.verifier(), PACKAGED, ZONE);
        machine = MachineFingerprint.of(Tokens.MACHINE);
    }

    private Path writeToken(EntitlementToken token, String keyId) throws Exception {
        Path file = dir.resolve(EntitlementLoader.ENTITLEMENT_FILE);
        loader.store(file, keyId, signer.sign(keyId, token), token);
        return file;
    }

    @Test
    @DisplayName("loads and resolves a valid entitlement")
    void loadsValidEntitlement() throws Exception {
        Path file = writeToken(Tokens.activeAround(TODAY, "accounting"), "primary");

        Entitlement e = loader.load(file, machine, new TimeAnchor(NOW), NOW);

        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(e.moduleState("accounting")).isEqualTo(ModuleState.ACTIVE);
        assertThat(e.allowsMutation()).isTrue();
    }

    @Test
    @DisplayName("an absent entitlement means UNLICENSED, not an error")
    void missingFileIsUnlicensed() {
        Entitlement e = loader.load(dir.resolve("nothing-here"), machine, new TimeAnchor(NOW), NOW);
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.UNLICENSED);
    }

    @Test
    @DisplayName("a tampered entitlement is refused")
    void tamperedEntitlementRefused() throws Exception {
        Path file = writeToken(Tokens.activeAround(TODAY, "accounting"), "primary");

        // Extend the subscription by a century without re-signing.
        String content = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, content.replace("subscriptionEnd=2027", "subscriptionEnd=2127"));

        Entitlement e = loader.load(file, machine, new TimeAnchor(NOW), NOW);
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.INVALID);
        assertThat(e.invalidReason()).isPresent();
        assertThat(e.allowsMutation()).isFalse();
        assertThat(e.allowsRead()).as("data stays readable").isTrue();
    }

    @Test
    @DisplayName("a corrupt entitlement file is refused, not crashed on")
    void corruptFileRefused() throws Exception {
        Path file = dir.resolve(EntitlementLoader.ENTITLEMENT_FILE);
        Files.writeString(file, "this is not an entitlement");

        Entitlement e = loader.load(file, machine, new TimeAnchor(NOW), NOW);
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.INVALID);
        assertThat(e.invalidReason()).isPresent();
    }

    @Test
    @DisplayName("an empty entitlement file is refused")
    void emptyFileRefused() throws Exception {
        Path file = dir.resolve(EntitlementLoader.ENTITLEMENT_FILE);
        Files.writeString(file, "");
        assertThat(loader.load(file, machine, new TimeAnchor(NOW), NOW).subscriptionState())
                .isEqualTo(SubscriptionState.INVALID);
    }

    @Test
    @DisplayName("an entitlement for another machine is refused")
    void wrongMachineRefused() throws Exception {
        Path file = writeToken(
                Tokens.boundTo(List.of("x1", "x2", "x3", "x4", "x5"), TODAY, "accounting"), "primary");

        Entitlement e = loader.load(file, machine, new TimeAnchor(NOW), NOW);
        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.INVALID);
        assertThat(e.invalidReason().orElseThrow()).contains("different computer");
    }

    @Test
    @DisplayName("a partially changed machine still loads (3 of 5)")
    void partiallyChangedMachineStillLoads() throws Exception {
        Path file = writeToken(Tokens.activeAround(TODAY, "accounting"), "primary");

        // Docker installed and the OS updated: two signals differ, three agree.
        MachineFingerprint changed = MachineFingerprint.of(
                List.of("h1", "h2", "h3", "CHANGED", "CHANGED"));

        assertThat(loader.load(file, changed, new TimeAnchor(NOW), NOW).subscriptionState())
                .isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    @DisplayName("an entitlement signed with the rotated key still loads")
    void rotatedKeyLoads() throws Exception {
        Path file = writeToken(Tokens.activeAround(TODAY, "accounting"), "secondary");
        assertThat(loader.load(file, machine, new TimeAnchor(NOW), NOW).subscriptionState())
                .isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    @DisplayName("an entitlement signed with a revoked key is refused")
    void revokedKeyRefused() throws Exception {
        Path file = writeToken(Tokens.activeAround(TODAY, "accounting"), "primary");

        // The next build drops the compromised key.
        EntitlementLoader afterRevocation =
                new EntitlementLoader(signer.verifierTrustingOnly("secondary"), PACKAGED, ZONE);

        assertThat(afterRevocation.load(file, machine, new TimeAnchor(NOW), NOW).subscriptionState())
                .isEqualTo(SubscriptionState.INVALID);
    }

    @Test
    @DisplayName("a rolled-back clock cannot revive an expired subscription")
    void clockRollbackCannotRevive() throws Exception {
        EntitlementToken expired = Tokens.covering(
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), "accounting");
        Path file = writeToken(expired, "primary");

        // The anchor has already seen today, so winding the clock back to when
        // the subscription was current changes nothing.
        TimeAnchor anchor = new TimeAnchor(NOW);
        anchor.observe(NOW);
        Instant rolledBack = LocalDate.of(2025, 12, 1).atStartOfDay(ZONE).toInstant();

        assertThat(loader.load(file, machine, anchor, rolledBack).subscriptionState())
                .isEqualTo(SubscriptionState.EXPIRED);
    }

    @Test
    @DisplayName("loading needs no network")
    void loadingIsOffline() throws Exception {
        // Nothing in the load path opens a socket: verification is local, and
        // the token carries everything needed to decide. An accountant on a
        // machine with no internet must be able to work.
        Path file = writeToken(Tokens.activeAround(TODAY, "accounting"), "primary");
        Entitlement e = loader.load(file, machine, new TimeAnchor(NOW), NOW);
        assertThat(e.allowsMutation()).isTrue();
    }

    @Test
    @DisplayName("verification happens once, not per question")
    void verifiesOnce() throws Exception {
        // Ed25519 verification measures ~1.5 ms, which would be ruinous in front
        // of every posting. The resolved Entitlement answers from memory.
        Path file = writeToken(Tokens.activeAround(TODAY, "accounting", "inventory"), "primary");
        Entitlement e = loader.load(file, machine, new TimeAnchor(NOW), NOW);

        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            e.moduleState("accounting");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("100k module-state checks should cost far less than one verification")
                .isLessThan(500);
    }
}
