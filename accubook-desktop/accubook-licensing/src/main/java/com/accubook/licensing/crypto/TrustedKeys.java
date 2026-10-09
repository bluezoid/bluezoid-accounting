package com.accubook.licensing.crypto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The public keys compiled into this build.
 *
 * <p>Two of them, from the first release, so the signing key can be rotated
 * without abandoning installations already in the field (decision P3-4).
 *
 * <p>These are <em>public</em> keys: embedding them is the intended design, and
 * an attacker gains nothing from reading them. The corresponding private key
 * never leaves the licence server, and should live in an HSM or managed KMS -
 * it is the single most sensitive secret in the business, because it can forge
 * every licence in the field.
 *
 * <p>The values below are development keys. Production builds replace them at
 * build time, and a release must fail if they are still the development ones.
 */
public final class TrustedKeys {

    /** Key ids appear in tokens, so the client knows which key to try first. */
    public static final String PRIMARY_KEY_ID = "acb-2026-primary";
    public static final String SECONDARY_KEY_ID = "acb-2026-secondary";

    // Development keys. Real Ed25519 public keys, but generated for testing:
    // they sign nothing customers rely on, and the matching private keys exist
    // only on a developer machine. A release build replaces both, and the
    // release process must fail if these values are still present.
    private static final String DEV_PRIMARY =
            "MCowBQYDK2VwAyEAMRVuWMs8dIWCYr54NZKME0R9xByXWIhLCRiKKNiol+Q=";
    private static final String DEV_SECONDARY =
            "MCowBQYDK2VwAyEArmWwLbuEHEyYnH4HfbVJpEwgTiMT/4kjDyt4+GzE8cc=";

    private TrustedKeys() {
    }

    /** Trusted keys, primary first. */
    public static Map<String, String> forThisBuild() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(PRIMARY_KEY_ID, DEV_PRIMARY);
        keys.put(SECONDARY_KEY_ID, DEV_SECONDARY);
        return keys;
    }

    /** A verifier over this build's trusted keys. */
    public static SignatureVerifier verifier() {
        return SignatureVerifier.withKeys(forThisBuild());
    }
}
