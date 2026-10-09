package com.accubook.licensing.support;

import com.accubook.licensing.crypto.SignatureVerifier;
import com.accubook.licensing.token.EntitlementToken;
import com.accubook.licensing.token.TokenCodec;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stands in for the licence server.
 *
 * <p>The server is Phase 13; Phase 3 defines and consumes the protocol. This
 * harness signs tokens with throwaway keys so the whole client path is testable
 * now, without server infrastructure and without shipping a private key.
 */
public final class TestSigner {

    private final Map<String, KeyPair> keys = new LinkedHashMap<>();

    /** Generate and register a key under {@code keyId}. */
    public TestSigner withKey(String keyId) {
        try {
            keys.put(keyId, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
            return this;
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 unavailable", e);
        }
    }

    /** A verifier trusting the registered keys, in registration order. */
    public SignatureVerifier verifier() {
        Map<String, String> encoded = new LinkedHashMap<>();
        keys.forEach((id, pair) ->
                encoded.put(id, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())));
        return SignatureVerifier.withKeys(encoded);
    }

    /** A verifier trusting only the named subset - used to model key revocation. */
    public SignatureVerifier verifierTrustingOnly(String... keyIds) {
        Map<String, String> encoded = new LinkedHashMap<>();
        for (String id : keyIds) {
            KeyPair pair = keys.get(id);
            if (pair == null) {
                throw new IllegalArgumentException("No such test key: " + id);
            }
            encoded.put(id, Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        }
        return SignatureVerifier.withKeys(encoded);
    }

    public byte[] sign(String keyId, EntitlementToken token) {
        return signBytes(keyId, TokenCodec.encode(token).getBytes(StandardCharsets.UTF_8));
    }

    public byte[] signBytes(String keyId, byte[] payload) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(keys.get(keyId).getPrivate());
            signature.update(payload);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Signing failed", e);
        }
    }

    /** The base64 X.509 public key, as it would be embedded in a build. */
    public String publicKeyBase64(String keyId) {
        return Base64.getEncoder().encodeToString(keys.get(keyId).getPublic().getEncoded());
    }
}
