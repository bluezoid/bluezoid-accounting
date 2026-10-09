package com.accubook.licensing.crypto;

import com.accubook.licensing.support.TestSigner;
import com.accubook.licensing.support.Tokens;
import com.accubook.licensing.token.TokenCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signature verification and public-key rotation (decision P3-4).
 */
@DisplayName("Signature verifier")
class SignatureVerifierTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);

    @Test
    @DisplayName("accepts a correctly signed payload")
    void acceptsValidSignature() {
        TestSigner signer = new TestSigner().withKey("primary");
        byte[] payload = TokenCodec.encode(Tokens.activeAround(TODAY)).getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.signBytes("primary", payload);

        assertThat(signer.verifier().verify(payload, signature, "primary")).contains("primary");
    }

    @Test
    @DisplayName("rejects a tampered payload")
    void rejectsTamperedPayload() {
        TestSigner signer = new TestSigner().withKey("primary");
        byte[] payload = TokenCodec.encode(Tokens.activeAround(TODAY)).getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.signBytes("primary", payload);

        // Extend the subscription by a century - the classic forgery attempt.
        String tampered = new String(payload, StandardCharsets.UTF_8)
                .replace("subscriptionEnd=2027", "subscriptionEnd=2127");

        assertThat(signer.verifier()
                .verify(tampered.getBytes(StandardCharsets.UTF_8), signature, "primary"))
                .isEmpty();
    }

    @Test
    @DisplayName("rejects a signature from an untrusted key")
    void rejectsUntrustedKey() {
        TestSigner attacker = new TestSigner().withKey("forged");
        TestSigner ours = new TestSigner().withKey("primary");

        byte[] payload = "anything".getBytes(StandardCharsets.UTF_8);
        byte[] forgedSignature = attacker.signBytes("forged", payload);

        assertThat(ours.verifier().verify(payload, forgedSignature, null)).isEmpty();
    }

    @Test
    @DisplayName("rejects a token naming a key this build does not trust")
    void rejectsUnknownKeyId() {
        TestSigner signer = new TestSigner().withKey("primary");
        byte[] payload = "anything".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.signBytes("primary", payload);

        assertThat(signer.verifier().verify(payload, signature, "some-other-key")).isEmpty();
    }

    @Test
    @DisplayName("a build trusting two keys accepts either (P3-4)")
    void acceptsEitherOfTwoKeys() {
        TestSigner signer = new TestSigner().withKey("primary").withKey("secondary");
        byte[] payload = "anything".getBytes(StandardCharsets.UTF_8);

        assertThat(signer.verifier().verify(payload, signer.signBytes("primary", payload), "primary"))
                .contains("primary");
        assertThat(signer.verifier().verify(payload, signer.signBytes("secondary", payload), "secondary"))
                .contains("secondary");
    }

    @Test
    @DisplayName("rotation: the server switches keys and existing builds keep working")
    void rotationWorksForAlreadyShippedBuilds() {
        // The scenario P3-4 exists for. A build shipped trusting both keys; the
        // primary is later compromised and the server switches to the secondary.
        // Installations already in the field must keep verifying, because they
        // cannot be taught a new key without an update - and an update signed
        // with the compromised key would be worthless.
        TestSigner signer = new TestSigner().withKey("primary").withKey("secondary");
        byte[] payload = "post-rotation token".getBytes(StandardCharsets.UTF_8);
        byte[] signedWithSecondary = signer.signBytes("secondary", payload);

        assertThat(signer.verifier().verify(payload, signedWithSecondary, "secondary"))
                .as("a shipped build accepts the rotated key without an update")
                .contains("secondary");
    }

    @Test
    @DisplayName("revocation: dropping a key from the next build stops its tokens")
    void revokedKeyStopsVerifying() {
        TestSigner signer = new TestSigner().withKey("compromised").withKey("secondary");
        byte[] payload = "old token".getBytes(StandardCharsets.UTF_8);
        byte[] signedWithCompromised = signer.signBytes("compromised", payload);

        // The next build ships without the compromised key.
        assertThat(signer.verifierTrustingOnly("secondary")
                .verify(payload, signedWithCompromised, "compromised"))
                .isEmpty();
    }

    @Test
    @DisplayName("reports which keys this build trusts")
    void reportsTrustedKeys() {
        TestSigner signer = new TestSigner().withKey("primary").withKey("secondary");
        assertThat(signer.verifier().trustedKeyIds()).containsExactly("primary", "secondary");
    }

    @Test
    @DisplayName("the build ships two keys from v1")
    void buildShipsTwoKeys() {
        assertThat(TrustedKeys.forThisBuild()).hasSize(2);
        assertThat(TrustedKeys.verifier().trustedKeyIds())
                .containsExactly(TrustedKeys.PRIMARY_KEY_ID, TrustedKeys.SECONDARY_KEY_ID);
    }

    @Test
    @DisplayName("rejects a garbage signature without throwing")
    void handlesGarbageSignature() {
        TestSigner signer = new TestSigner().withKey("primary");
        assertThat(signer.verifier()
                .verify("payload".getBytes(StandardCharsets.UTF_8), new byte[]{1, 2, 3}, "primary"))
                .isEmpty();
    }
}
