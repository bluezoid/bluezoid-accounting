package com.accubook.licensing.crypto;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies entitlement signatures against the public keys this build trusts.
 *
 * <h2>Ed25519, from the JDK</h2>
 * Corretto 17 provides Ed25519 natively - verified: 44-byte X.509 public key,
 * 64-byte signature. So there is no third-party crypto dependency, and nothing
 * extra in the jlink image.
 *
 * <h2>Why more than one key (decision P3-4)</h2>
 * A build can only verify signatures made by a key it already knows. If the
 * signing key is ever compromised and only one key was embedded, every
 * already-shipped installation becomes unfixable: it cannot be taught the
 * replacement key without an application update, and an update signed with the
 * compromised key is worth nothing.
 *
 * <p>Shipping two keys from the first release makes rotation possible: the
 * server switches to the secondary key, existing installations already accept
 * it, and the compromised key is dropped from the next build. It costs 44 bytes
 * and cannot be retrofitted.
 *
 * <p>Keys are tried in order, so the primary is the common path. A revoked key
 * is simply omitted from the next build's list, at which point tokens signed
 * with it stop verifying.
 */
public final class SignatureVerifier {

    private final Map<String, PublicKey> trustedKeys;

    private SignatureVerifier(Map<String, PublicKey> trustedKeys) {
        this.trustedKeys = trustedKeys;
    }

    /**
     * Build a verifier from base64 X.509 public keys, most-preferred first.
     *
     * @param encodedKeys key id to base64 X.509 encoding
     */
    public static SignatureVerifier withKeys(Map<String, String> encodedKeys) {
        Objects.requireNonNull(encodedKeys, "encodedKeys");
        if (encodedKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted public key is required");
        }
        // LinkedHashMap, not Map.copyOf: copyOf gives no ordering guarantee, and
        // order is meaningful here - the primary key is tried first, so the
        // common path costs one verification rather than several.
        Map<String, PublicKey> parsed = new LinkedHashMap<>();
        encodedKeys.forEach((id, encoded) -> parsed.put(id, decodePublicKey(id, encoded)));
        return new SignatureVerifier(Collections.unmodifiableMap(parsed));
    }

    /** Ids of the keys this build trusts, for diagnostics. */
    public List<String> trustedKeyIds() {
        return List.copyOf(trustedKeys.keySet());
    }

    /**
     * Verify {@code signature} over {@code payload}.
     *
     * @param keyId the key the server says it signed with, or {@code null} to
     *              try every trusted key
     * @return the id of the key that verified it, or empty if none did
     */
    public java.util.Optional<String> verify(byte[] payload, byte[] signature, String keyId) {
        if (keyId != null) {
            PublicKey named = trustedKeys.get(keyId);
            if (named == null) {
                // The token names a key this build does not trust - either it is
                // forged, or the build predates a rotation. Both mean "refuse".
                return java.util.Optional.empty();
            }
            return verifyWith(named, payload, signature)
                    ? java.util.Optional.of(keyId)
                    : java.util.Optional.empty();
        }

        for (Map.Entry<String, PublicKey> entry : trustedKeys.entrySet()) {
            if (verifyWith(entry.getValue(), payload, signature)) {
                return java.util.Optional.of(entry.getKey());
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean verifyWith(PublicKey key, byte[] payload, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            return verifier.verify(signature);
        } catch (Exception e) {
            // A malformed signature is a failed verification, not an error worth
            // propagating: the caller's response is identical either way.
            return false;
        }
    }

    private static PublicKey decodePublicKey(String id, String base64) {
        try {
            byte[] encoded = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new IllegalArgumentException("Public key '" + id + "' could not be read", e);
        }
    }
}
