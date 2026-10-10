package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedRulePackTest {

    private static final KeyPair KEYS = generate();
    private static final Map<String, String> TRUSTED =
            Map.of("test-key", Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(String body, KeyPair keys, String keyId) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        return SignedRulePack.HEADER + "\nkey=" + keyId + "\nsignature="
                + Base64.getEncoder().encodeToString(signer.sign()) + "\n---\n" + body;
    }

    @Test
    @DisplayName("a correctly signed pack verifies and parses; the file round-trips byte for byte")
    void verifies() throws Exception {
        String file = sign(RulePackTest.SAMPLE, KEYS, "test-key");
        SignedRulePack.Verified verified = SignedRulePack.verify(file, TRUSTED);

        assertThat(verified.pack().version()).isEqualTo("2025.10.1");
        assertThat(verified.file()).isEqualTo(file);
    }

    @Test
    @DisplayName("one changed character - a rate - is detected")
    void tamperDetected() throws Exception {
        String file = sign(RulePackTest.SAMPLE, KEYS, "test-key").replace("0.1|5|ANNUAL", "0.0|5|ANNUAL");
        assertThatThrownBy(() -> SignedRulePack.verify(file, TRUSTED)).hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("refuses an unknown key, a key we do not trust, and an unsigned file")
    void untrusted() throws Exception {
        String other = sign(RulePackTest.SAMPLE, generate(), "test-key");
        assertThatThrownBy(() -> SignedRulePack.verify(other, TRUSTED)).hasMessageContaining("does not match");
        assertThatThrownBy(() -> SignedRulePack.verify(sign(RulePackTest.SAMPLE, KEYS, "rogue"), TRUSTED))
                .hasMessageContaining("unknown key");
        assertThatThrownBy(() -> SignedRulePack.verify(RulePackTest.SAMPLE, TRUSTED)).hasMessageContaining("Not a signed");
    }

    @Test
    @DisplayName("two rule-pack keys ship from the first build, distinct from the licence keys (P9-4)")
    void twoKeys() {
        assertThat(TrustedRulePackKeys.forThisBuild()).hasSize(2);
        assertThat(TrustedRulePackKeys.forThisBuild().values()).doesNotHaveDuplicates()
                .doesNotContain("MCowBQYDK2VwAyEAMRVuWMs8dIWCYr54NZKME0R9xByXWIhLCRiKKNiol+Q=",
                        "MCowBQYDK2VwAyEArmWwLbuEHEyYnH4HfbVJpEwgTiMT/4kjDyt4+GzE8cc=");
    }
}
