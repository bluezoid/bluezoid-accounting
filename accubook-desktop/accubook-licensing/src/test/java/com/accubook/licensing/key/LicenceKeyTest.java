package com.accubook.licensing.key;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Licence key")
class LicenceKeyTest {

    /** A key built so its check character is correct. */
    private static String validKey() {
        String payload = "ACB7X92KPQ41M8Z";
        return payload + LicenceKey.checkCharacter(payload);
    }

    @Test
    @DisplayName("accepts a well-formed key")
    void acceptsValidKey() {
        assertThat(LicenceKey.isValid(validKey())).isTrue();
    }

    @Test
    @DisplayName("accepts the hyphenated display form")
    void acceptsHyphenatedForm() {
        String key = validKey();
        String display = key.substring(0, 4) + "-" + key.substring(4, 8)
                + "-" + key.substring(8, 12) + "-" + key.substring(12);
        assertThat(LicenceKey.parse(display).normalised()).isEqualTo(key);
    }

    @Test
    @DisplayName("accepts lower case and stray spaces")
    void acceptsSloppyInput() {
        assertThat(LicenceKey.parse(" " + validKey().toLowerCase() + " ").normalised())
                .isEqualTo(validKey());
    }

    @Test
    @DisplayName("folds the ambiguous characters a customer is likely to mistype")
    void foldsAmbiguousCharacters() {
        // O/0 and I/1 are read wrong over the phone, so the excluded letters map
        // onto the digits they are mistaken for rather than being rejected.
        assertThat(LicenceKey.normalise("O1IL")).isEqualTo("0111");
    }

    @Test
    @DisplayName("rejects a mistyped key before any network call")
    void rejectsMistypedKey() {
        String key = validKey();
        String mistyped = key.substring(0, 6) + (key.charAt(6) == 'A' ? 'B' : 'A') + key.substring(7);
        assertThatThrownBy(() -> LicenceKey.parse(mistyped))
                .isInstanceOf(InvalidLicenceKeyException.class)
                .hasMessageContaining("mistyped");
    }

    @Test
    @DisplayName("detects a transposition")
    void detectsTransposition() {
        // Position weighting is what makes swapped characters detectable.
        String key = validKey();
        char[] chars = key.toCharArray();
        char first = chars[0];
        chars[0] = chars[1];
        chars[1] = first;
        if (new String(chars).equals(key)) {
            return; // the two characters happened to be identical
        }
        assertThat(LicenceKey.isValid(new String(chars))).isFalse();
    }

    @Test
    @DisplayName("reports the wrong length clearly")
    void rejectsWrongLength() {
        assertThatThrownBy(() -> LicenceKey.parse("ACB7-X92K"))
                .isInstanceOf(InvalidLicenceKeyException.class)
                .hasMessageContaining("16 characters");
    }

    @Test
    @DisplayName("rejects a character outside the alphabet")
    void rejectsBadCharacter() {
        assertThatThrownBy(() -> LicenceKey.parse("ACB7-X92K-PQ41-M8Z!"))
                .isInstanceOf(InvalidLicenceKeyException.class);
    }

    @Test
    @DisplayName("rejects empty input with a usable message")
    void rejectsEmpty() {
        assertThatThrownBy(() -> LicenceKey.parse("  "))
                .isInstanceOf(InvalidLicenceKeyException.class)
                .hasMessageContaining("Enter a licence key");
    }

    @Test
    @DisplayName("formats for display")
    void formatsForDisplay() {
        assertThat(LicenceKey.parse(validKey()).formatted()).matches("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}");
    }

    @Test
    @DisplayName("toString redacts the key, because it reaches log files")
    void toStringRedacts() {
        LicenceKey key = LicenceKey.parse(validKey());
        assertThat(key.toString()).isEqualTo("ACB7-****-****-****");
        assertThat(key.toString()).doesNotContain(key.normalised().substring(4));
    }
}
