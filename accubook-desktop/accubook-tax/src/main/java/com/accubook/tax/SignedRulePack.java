package com.accubook.tax;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * A rule pack as distributed: a short signature header, a separator, then the
 * pack's text. The signature covers the text's exact bytes, so nothing has to be
 * canonicalised and a single changed character is detected.
 *
 * <pre>
 * accubook-rulepack-signature/1
 * key=acb-rulepack-dev-primary
 * signature=BASE64
 * ---
 * format=accubook-rulepack/1
 * ...
 * </pre>
 */
public final class SignedRulePack {

    public static final String HEADER = "accubook-rulepack-signature/1";
    private static final String SEPARATOR = "\n---\n";

    private SignedRulePack() {
    }

    /** Split, verify against {@code trustedKeys}, parse. Refuses anything unsigned, unknown or altered. */
    public static Verified verify(String file, Map<String, String> trustedKeys) {
        Objects.requireNonNull(file, "file");
        String normalised = file.replace("\r\n", "\n");
        int split = normalised.indexOf(SEPARATOR);
        if (!normalised.startsWith(HEADER + "\n") || split < 0) {
            throw new RulePackException("Not a signed AccuBook rule pack");
        }
        String keyId = null;
        String signature = null;
        for (String line : normalised.substring(HEADER.length() + 1, split).split("\n")) {
            if (line.startsWith("key=")) {
                keyId = line.substring(4).strip();
            } else if (line.startsWith("signature=")) {
                signature = line.substring(10).strip();
            } else if (!line.isBlank()) {
                throw new RulePackException("Unexpected line in the signature header: " + line);
            }
        }
        if (keyId == null || signature == null) {
            throw new RulePackException("The rule pack is not signed");
        }
        String publicKey = trustedKeys.get(keyId);
        if (publicKey == null) {
            throw new RulePackException("The rule pack is signed by an unknown key (" + keyId + ")");
        }
        String body = normalised.substring(split + SEPARATOR.length());
        if (!signatureValid(publicKey, body.getBytes(StandardCharsets.UTF_8), signature)) {
            throw new RulePackException("The rule pack's signature does not match its contents");
        }
        return new Verified(keyId, signature, body, RulePack.parse(body));
    }

    /** A pack that passed verification, with the exact text that was signed. */
    public record Verified(String keyId, String signature, String body, RulePack pack) {

        /** The distributable file again, byte for byte. */
        public String file() {
            return HEADER + "\nkey=" + keyId + "\nsignature=" + signature + SEPARATOR + body;
        }
    }

    private static boolean signatureValid(String publicKeyBase64, byte[] body, String signatureBase64) {
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(body);
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            return false;
        }
    }
}
