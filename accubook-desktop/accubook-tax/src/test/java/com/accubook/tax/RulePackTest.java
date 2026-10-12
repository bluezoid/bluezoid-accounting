package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RulePackTest {

    static final String SAMPLE = """
            format=accubook-rulepack/1
            version=2025.10.1
            issued=2026-10-10
            status=UNVERIFIED
            source=https://example.org/one
            source=https://example.org/two

            [states]
            27|Maharashtra|STATE
            38|Ladakh|UNION_TERRITORY
            97|Other Territory|SPECIAL
            # GST 2.0: 12 and 28 end, 40 begins
            [gst-slabs]
            2017-07-01|2025-09-21|0,0.25,3,5,12,18,28
            2025-09-22||0,0.25,3,5,18,40
            [thresholds]
            B2CL_INVOICE|2017-07-01||250000
            COMPOSITION_LIMIT|2019-04-01||15000000
            COMPOSITION_LIMIT_SPECIAL_STATES|2019-04-01||7500000
            [tds]
            194J|2020-04-01|2025-03-31|10|20|SINGLE_OR_ANNUAL|30000||Professional fees
            194J|2025-04-01||10|20|SINGLE_OR_ANNUAL|50000||Professional fees
            194Q|2021-07-01||0.1|5|ANNUAL_EXCESS||5000000|Purchase of goods
            [tcs]
            206C(1H)|2020-10-01|2025-03-31|0.1|1|ANNUAL_EXCESS||5000000|Sale of goods
            [composition-special-states]
            38|2019-04-01|
            [uqc]
            NOS|Numbers
            KGS|Kilograms
            """;

    @Test
    @DisplayName("parses header, sources and every section")
    void parses() {
        RulePack pack = RulePack.parse(SAMPLE);

        assertThat(pack.version()).isEqualTo("2025.10.1");
        assertThat(pack.status()).isEqualTo("UNVERIFIED");
        assertThat(pack.sources()).hasSize(2);
        assertThat(pack.state("38")).hasValueSatisfying(s -> assertThat(s.kind()).isEqualTo(StateCode.Kind.UNION_TERRITORY));
        assertThat(pack.threshold("B2CL_INVOICE", LocalDate.of(2026, 1, 1))).isEqualByComparingTo("250000");
    }

    @Test
    @DisplayName("GST 2.0: 28% is permitted on 21 Sep 2025 and not on 22 Sep; 40% the other way round")
    void slabsByDate() {
        RulePack pack = RulePack.parse(SAMPLE);

        assertThat(pack.gstSlabsOn(LocalDate.of(2025, 9, 21)).permits(new BigDecimal("28"))).isTrue();
        assertThat(pack.gstSlabsOn(LocalDate.of(2025, 9, 22)).permits(new BigDecimal("28"))).isFalse();
        assertThat(pack.gstSlabsOn(LocalDate.of(2025, 9, 22)).permits(new BigDecimal("40.00"))).isTrue();
        assertThatThrownBy(() -> pack.gstSlabsOn(LocalDate.of(2017, 6, 30))).isInstanceOf(RulePackException.class);
    }

    @Test
    @DisplayName("sections are effective-dated: 194J's threshold changes; 206C(1H) stops applying (R-17)")
    void sectionsByDate() {
        RulePack pack = RulePack.parse(SAMPLE);

        assertThat(pack.section(WithholdingSection.Kind.TDS, "194J", LocalDate.of(2025, 3, 31)).orElseThrow().threshold())
                .isEqualByComparingTo("30000");
        assertThat(pack.section(WithholdingSection.Kind.TDS, "194J", LocalDate.of(2025, 4, 1)).orElseThrow().threshold())
                .isEqualByComparingTo("50000");
        assertThat(pack.section(WithholdingSection.Kind.TCS, "206C(1H)", LocalDate.of(2025, 3, 31))).isPresent();
        assertThat(pack.section(WithholdingSection.Kind.TCS, "206C(1H)", LocalDate.of(2025, 4, 1))).isEmpty();
    }

    @Test
    @DisplayName("refuses overlapping entries, with the line number for malformed ones")
    void strict() {
        assertThatThrownBy(() -> RulePack.parse(SAMPLE.replace("2025-04-01||10|20", "2025-03-01||10|20")))
                .hasMessageContaining("in force at once");
        assertThatThrownBy(() -> RulePack.parse(SAMPLE.replace("27|Maharashtra|STATE", "27|Maharashtra")))
                .hasMessageContaining("line 9").hasMessageContaining("expected 3 fields");
        assertThatThrownBy(() -> RulePack.parse(SAMPLE.replace("[tcs]", "[vat]"))).hasMessageContaining("unknown section");
        assertThatThrownBy(() -> RulePack.parse(SAMPLE.replace("format=accubook-rulepack/1", "format=other")))
                .hasMessageContaining("Not an AccuBook rule pack");
        assertThatThrownBy(() -> RulePack.parse(SAMPLE + "38|Ladakh again|STATE\n".replace("38", "[states]\n38")))
                .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("versions compare numerically: 2025.10 follows 2025.9")
    void versions() {
        assertThat(RulePack.compareVersions("2025.10", "2025.9")).isPositive();
        assertThat(RulePack.compareVersions("2025.10.0", "2025.10")).isZero();
    }

    @Test
    @DisplayName("the composition limit is lower in a listed special-category state")
    void compositionLimit() {
        RulePack pack = RulePack.parse(SAMPLE);
        LocalDate on = LocalDate.of(2026, 4, 1);

        assertThat(pack.compositionLimit("27", on)).isEqualByComparingTo("15000000");
        assertThat(pack.compositionLimit("38", on)).isEqualByComparingTo("7500000");
    }

    @Test
    @DisplayName("a special state must be a known state")
    void specialStateKnown() {
        assertThatThrownBy(() -> RulePack.parse(SAMPLE.replace("38|2019-04-01|\n", "55|2019-04-01|\n")))
                .hasMessageContaining("not in [states]");
    }

    @Test
    @DisplayName("UQC codes are three capital letters, each listed once")
    void uqc() {
        RulePack pack = RulePack.parse(SAMPLE);
        assertThat(pack.uqc("KGS")).hasValueSatisfying(u -> assertThat(u.description()).isEqualTo("Kilograms"));
        assertThat(pack.uqc("kg")).isEmpty();
        assertThatThrownBy(() -> RulePack.parse(SAMPLE.replace("KGS|Kilograms", "NOS|Numbers again")))
                .hasMessageContaining("UQC appears twice");
        assertThatThrownBy(() -> RulePack.parse(SAMPLE.replace("KGS|Kilograms", "Kg|Kilograms")))
                .hasMessageContaining("three capital letters");
    }
}
