package com.accubook.tax;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public keys that may sign a rule pack - separate from the licence keys (P9-4),
 * so a compromise of one does not let anyone forge the other.
 *
 * <p>Two keys from the first release, like licences: rotation cannot be added
 * after customers' installations are in the field.
 *
 * <p><strong>Development keys.</strong> Real Ed25519 public keys; the matching
 * private keys live only in {@code ~/.accubook-dev/} on a developer machine and
 * are never committed. A release replaces both, and the release gate (V-1, P16)
 * must fail while these are present.
 */
public final class TrustedRulePackKeys {

    public static final String PRIMARY_KEY_ID = "acb-rulepack-dev-primary";
    public static final String SECONDARY_KEY_ID = "acb-rulepack-dev-secondary";

    static final String DEV_PRIMARY = "MCowBQYDK2VwAyEAWJNg8MBTmllhi5syzvOdwa1LCs4DGfHDgH83wIjuT5E=";
    static final String DEV_SECONDARY = "MCowBQYDK2VwAyEA/E/LFnfdoAcbDYCKEmMGNVUB2S6TMlNBKi/XwtqGk3U=";

    private TrustedRulePackKeys() {
    }

    /** Key id to base64 X.509 public key, primary first. */
    public static Map<String, String> forThisBuild() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put(PRIMARY_KEY_ID, DEV_PRIMARY);
        keys.put(SECONDARY_KEY_ID, DEV_SECONDARY);
        return keys;
    }
}
