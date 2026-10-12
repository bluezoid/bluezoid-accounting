package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The pack shipped in this build. Its data is UNVERIFIED (V-1); these tests check the plumbing and invariants. */
class BundledRulePackTest {

    private static final SignedRulePack.Verified BUNDLED = RulePacks.bundled(TrustedRulePackKeys.forThisBuild());

    @Test
    @DisplayName("verifies with this build's keys and is exactly the reviewable source file")
    void matchesSource() throws Exception {
        String source = Files.readString(Path.of("rulepack", "rulepack-" + BUNDLED.pack().version() + ".txt"),
                StandardCharsets.UTF_8);
        assertThat(BUNDLED.body()).isEqualTo(source);
    }

    @Test
    @DisplayName("is honestly marked unverified until a chartered accountant signs it off (V-1)")
    void markedUnverified() {
        assertThat(BUNDLED.pack().status()).isEqualTo("UNVERIFIED");
        assertThat(BUNDLED.pack().sources()).isNotEmpty();
    }

    @Test
    @DisplayName("one state table, including Ladakh 38, Other Territory 97 and Centre 99")
    void states() {
        RulePack pack = BUNDLED.pack();
        assertThat(pack.state("38")).isPresent();
        assertThat(pack.state("97")).isPresent();
        assertThat(pack.state("99")).isPresent();
        assertThat(pack.state("04").orElseThrow().kind()).isEqualTo(StateCode.Kind.UNION_TERRITORY);
        assertThat(pack.state("07").orElseThrow().kind()).as("Delhi has a legislature: SGST")
                .isEqualTo(StateCode.Kind.STATE);
    }

    @Test
    @DisplayName("every day from 1 July 2017 has a slab set, and every section in force has one entry")
    void coverage() {
        RulePack pack = BUNDLED.pack();
        for (LocalDate d = LocalDate.of(2017, 7, 1); d.isBefore(LocalDate.of(2027, 4, 1)); d = d.plusDays(1)) {
            pack.gstSlabsOn(d);
        }
        assertThat(pack.gstSlabsOn(LocalDate.of(2025, 9, 22)).permits(new BigDecimal("40"))).isTrue();
        assertThat(pack.section(WithholdingSection.Kind.TCS, "206C(1H)", LocalDate.of(2025, 4, 1))).isEmpty();
        assertThat(pack.section(WithholdingSection.Kind.TDS, "194I-LAND", LocalDate.of(2025, 4, 1)).orElseThrow()
                .thresholdType()).isEqualTo(WithholdingSection.ThresholdType.MONTHLY);
    }

    @Test
    @DisplayName("composition: 1.5 crore, 75 lakh in the eight special-category states (e.g. Sikkim 11, Uttarakhand 05)")
    void compositionLimits() {
        RulePack pack = BUNDLED.pack();
        java.time.LocalDate on = java.time.LocalDate.of(2026, 4, 1);
        assertThat(pack.compositionLimit("27", on)).isEqualByComparingTo("15000000");
        assertThat(pack.compositionLimit("11", on)).isEqualByComparingTo("7500000");
        assertThat(pack.compositionLimit("05", on)).isEqualByComparingTo("7500000");
        assertThat(pack.compositionLimit("02", on)).as("Himachal Pradesh left the list in 2019")
                .isEqualByComparingTo("15000000");
        assertThat(pack.compositionSpecialStates()).hasSize(8);
    }

    @Test
    @DisplayName("the UQC list has the common codes, OTH included")
    void uqcList() {
        assertThat(BUNDLED.pack().uqcs()).hasSize(45);
        assertThat(BUNDLED.pack().uqc("NOS")).isPresent();
        assertThat(BUNDLED.pack().uqc("OTH")).isPresent();
    }
}
