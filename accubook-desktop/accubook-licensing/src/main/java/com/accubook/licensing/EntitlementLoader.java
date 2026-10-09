package com.accubook.licensing;

import com.accubook.licensing.crypto.SignatureVerifier;
import com.accubook.licensing.machine.MachineFingerprint;
import com.accubook.licensing.time.TimeAnchor;
import com.accubook.licensing.token.EntitlementToken;
import com.accubook.licensing.token.TokenCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * Loads and verifies the stored entitlement, once.
 *
 * <p>This is the only place a signature is checked. Everything downstream reads
 * the immutable {@link Entitlement} it produces, which is why an accounting
 * operation costs no cryptography.
 *
 * <h2>Failure is never destructive</h2>
 * Every failure path here returns a read-only {@link Entitlement}. None of them
 * deletes, moves, migrates or rewrites a company database. A customer whose
 * entitlement file is corrupt has a licensing problem, not a data problem, and
 * must still be able to open their books and export them.
 *
 * <p>The stored file format is three lines: the key id used to sign, the base64
 * signature, then the canonical token body.
 */
public final class EntitlementLoader {

    /** Serialised entitlement, on disk beside the installation state. */
    public static final String ENTITLEMENT_FILE = "entitlement.acb";

    private final SignatureVerifier verifier;
    private final Set<String> packagedModules;
    private final ZoneId zone;

    /**
     * A loader trusting the public keys compiled into this build.
     *
     * <p>The usual construction. The {@code crypto} package is not exported, so
     * callers cannot - and need not - assemble a verifier themselves.
     */
    public static EntitlementLoader forThisBuild(Set<String> packagedModules, ZoneId zone) {
        return new EntitlementLoader(
                com.accubook.licensing.crypto.TrustedKeys.verifier(), packagedModules, zone);
    }

    public EntitlementLoader(SignatureVerifier verifier, Set<String> packagedModules, ZoneId zone) {
        this.verifier = verifier;
        this.packagedModules = Set.copyOf(packagedModules);
        this.zone = zone;
    }

    /**
     * Load, verify and resolve the entitlement.
     *
     * @param entitlementFile the stored token
     * @param fingerprint     this machine, collected now
     * @param anchor          the monotonic clock; expiry is evaluated against it
     *                        rather than the raw system clock
     * @param systemNow       the system clock reading
     */
    public Entitlement load(Path entitlementFile,
                            MachineFingerprint fingerprint,
                            TimeAnchor anchor,
                            Instant systemNow) {

        if (!Files.exists(entitlementFile)) {
            return Entitlement.unlicensed(packagedModules);
        }

        String raw;
        try {
            raw = Files.readString(entitlementFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Entitlement.invalid("The licence file could not be read.", packagedModules);
        }

        StoredToken stored;
        try {
            stored = StoredToken.parse(raw);
        } catch (RuntimeException e) {
            return Entitlement.invalid("The licence file is damaged. Please reactivate.", packagedModules);
        }

        EntitlementToken token;
        try {
            token = TokenCodec.decode(stored.body());
        } catch (RuntimeException e) {
            return Entitlement.invalid("The licence file could not be read. Please reactivate.", packagedModules);
        }

        // Signature over the canonical body, which is exactly what was signed.
        Optional<String> verifiedBy = verifier.verify(
                stored.body().getBytes(StandardCharsets.UTF_8), stored.signature(), stored.keyId());
        if (verifiedBy.isEmpty()) {
            return Entitlement.invalid(
                    "This licence could not be verified. Please reactivate.", packagedModules);
        }

        // Machine binding. Fuzzy on purpose: a customer who changes a network
        // card or installs Docker has not changed machines.
        MachineFingerprint bound = MachineFingerprint.of(token.machineFingerprint());
        if (!fingerprint.matches(bound)) {
            return Entitlement.invalid(
                    "This licence is registered to a different computer. "
                            + "Contact support to move it.", packagedModules);
        }

        Instant effectiveNow = anchor.observe(systemNow);
        return Entitlement.resolve(token, packagedModules, effectiveNow.atZone(zone).toLocalDate());
    }

    /** Write a verified token to disk. Used by activation and renewal. */
    public void store(Path entitlementFile, String keyId, byte[] signature, EntitlementToken token)
            throws IOException {
        String body = TokenCodec.encode(token);
        String content = keyId + "\n"
                + Base64.getEncoder().encodeToString(signature) + "\n"
                + body;
        Files.createDirectories(entitlementFile.getParent());
        Files.writeString(entitlementFile, content, StandardCharsets.UTF_8);
    }

    /** The three-part on-disk form. */
    record StoredToken(String keyId, byte[] signature, String body) {

        static StoredToken parse(String raw) {
            int firstBreak = raw.indexOf('\n');
            int secondBreak = raw.indexOf('\n', firstBreak + 1);
            if (firstBreak < 0 || secondBreak < 0) {
                throw new IllegalArgumentException("Entitlement file is truncated");
            }
            String keyId = raw.substring(0, firstBreak).strip();
            byte[] signature = Base64.getDecoder().decode(raw.substring(firstBreak + 1, secondBreak).strip());
            String body = raw.substring(secondBreak + 1);
            if (keyId.isEmpty() || signature.length == 0 || body.isBlank()) {
                throw new IllegalArgumentException("Entitlement file is incomplete");
            }
            return new StoredToken(keyId, signature, body);
        }
    }
}
