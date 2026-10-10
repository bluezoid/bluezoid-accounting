package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GstinTest {

    private static final RulePack RULES = RulePack.parse(RulePackTest.SAMPLE);

    /** Builds a GSTIN with a correct check character, so tests never depend on a real taxpayer's number. */
    private static String valid(String first14) {
        return first14 + Gstin.checkCharacter(first14);
    }

    @Test
    @DisplayName("a GSTIN with the right shape, a known state and the right check character is valid")
    void valid() {
        String gstin = valid("27ABCDE1234F1Z");
        assertThat(Gstin.problem(gstin, RULES)).isEmpty();
        assertThat(Gstin.problem(gstin.toLowerCase(), RULES)).as("case-insensitive").isEmpty();
        assertThat(Gstin.stateOf(gstin)).isEqualTo("27");
        assertThat(Gstin.panOf(gstin)).isEqualTo("ABCDE1234F");
    }

    @Test
    @DisplayName("the check character catches a single mistyped character in the right shape")
    void checksum() {
        String gstin = valid("27ABCDE1234F1Z");
        String mistyped = gstin.substring(0, 7) + (gstin.charAt(7) == '1' ? '2' : '1') + gstin.substring(8);
        assertThat(Gstin.problem(mistyped, RULES)).hasValueSatisfying(p -> assertThat(p).contains("check character"));
    }

    @Test
    @DisplayName("a state code the rule pack does not know is refused")
    void unknownState() {
        assertThat(Gstin.problem(valid("55ABCDE1234F1Z"), RULES))
                .hasValueSatisfying(p -> assertThat(p).contains("not a state code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "27ABCDE1234F1Z", "27ABCDE1234F1X5", "2AABCDE1234F1Z5", "27ABCDE1234F0Z5"})
    @DisplayName("wrong length, missing Z, letter in the state code and entity 0 are refused")
    void format(String gstin) {
        assertThat(Gstin.problem(gstin, RULES)).isPresent();
    }

    @Test
    @DisplayName("the published check-digit example validates (fixed vector, not generated)")
    void knownVector() {
        // Commonly published illustrative GSTIN; independent of checkCharacter() above.
        assertThat(Gstin.checkCharacter("27AAPFU0939F1Z")).isEqualTo('V');
    }
}
