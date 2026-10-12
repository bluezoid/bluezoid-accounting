package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GstCalculatorTest {

    private static final RulePack RULES = RulePack.parse(RulePackTest.SAMPLE);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static List<LineTax> tax(String taxable, String rate, String cess, SupplyType type, StateCode.Kind kind) {
        return GstCalculator.lineTax(d(taxable), TaxCategory.TAXABLE, d(rate), cess == null ? null : d(cess), type, kind);
    }

    @Test
    @DisplayName("intra-state 18%: CGST 9% and SGST 9%")
    void intraState() {
        assertThat(tax("1000", "18", null, SupplyType.INTRA_STATE, StateCode.Kind.STATE)).containsExactly(
                new LineTax(GstComponent.CGST, d("9"), d("90.00")),
                new LineTax(GstComponent.SGST, d("9"), d("90.00")));
    }

    @Test
    @DisplayName("intra-state in a union territory without a legislature: UTGST instead of SGST")
    void unionTerritory() {
        assertThat(tax("1000", "18", null, SupplyType.INTRA_STATE, StateCode.Kind.UNION_TERRITORY))
                .extracting(LineTax::component).containsExactly(GstComponent.CGST, GstComponent.UTGST);
    }

    @Test
    @DisplayName("inter-state 18%: IGST 18%")
    void interState() {
        assertThat(tax("1000", "18", null, SupplyType.INTER_STATE, StateCode.Kind.STATE)).containsExactly(
                new LineTax(GstComponent.IGST, d("18"), d("180.00")));
    }

    @Test
    @DisplayName("0.25% splits into 0.125% halves without losing a digit")
    void quarterPercent() {
        assertThat(tax("1000", "0.25", null, SupplyType.INTRA_STATE, StateCode.Kind.STATE))
                .extracting(LineTax::rate, LineTax::amount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(d("0.125"), d("1.25")),
                        org.assertj.core.groups.Tuple.tuple(d("0.125"), d("1.25")));
    }

    @Test
    @DisplayName("B-4: each component is rounded half-up to the paisa on its own")
    void halfUpPerComponent() {
        // 5% of 0.50 = 0.025 -> 0.03 (half-even would give 0.02)
        assertThat(tax("0.50", "5", null, SupplyType.INTER_STATE, StateCode.Kind.STATE))
                .extracting(LineTax::amount).containsExactly(d("0.03"));
        // 5% of 0.30 = 0.015 -> 0.02; 2.5% of 0.30 = 0.0075 -> 0.01 each
        assertThat(tax("0.30", "5", null, SupplyType.INTER_STATE, StateCode.Kind.STATE))
                .extracting(LineTax::amount).containsExactly(d("0.02"));
        assertThat(tax("0.30", "5", null, SupplyType.INTRA_STATE, StateCode.Kind.STATE))
                .extracting(LineTax::amount).containsExactly(d("0.01"), d("0.01"));
    }

    @Test
    @DisplayName("cess is charged on the taxable value, intra- or inter-state")
    void cess() {
        assertThat(tax("1000", "28", "15", SupplyType.INTER_STATE, StateCode.Kind.STATE))
                .containsExactly(new LineTax(GstComponent.IGST, d("28"), d("280.00")),
                        new LineTax(GstComponent.CESS, d("15"), d("150.00")));
    }

    @Test
    @DisplayName("nil-rated, exempt and non-GST lines carry no tax")
    void untaxed() {
        for (TaxCategory category : List.of(TaxCategory.NIL_RATED, TaxCategory.EXEMPT, TaxCategory.NON_GST)) {
            assertThat(GstCalculator.lineTax(d("1000"), category, BigDecimal.ZERO, null, SupplyType.INTRA_STATE,
                    StateCode.Kind.STATE)).isEmpty();
        }
    }

    @Test
    @DisplayName("intra- and inter-state tax on the same line differ by at most one paisa")
    void splitAgreesWithWhole() {
        Random random = new Random(9);
        for (int i = 0; i < 5_000; i++) {
            BigDecimal taxable = BigDecimal.valueOf(random.nextInt(10_000_000), 2);
            BigDecimal rate = List.of(d("0.25"), d("3"), d("5"), d("18"), d("40")).get(random.nextInt(5));
            BigDecimal whole = tax(taxable.toPlainString(), rate.toPlainString(), null, SupplyType.INTER_STATE,
                    StateCode.Kind.STATE).get(0).amount();
            BigDecimal split = tax(taxable.toPlainString(), rate.toPlainString(), null, SupplyType.INTRA_STATE,
                    StateCode.Kind.STATE).stream().map(LineTax::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(split.subtract(whole).abs()).as("%s at %s", taxable, rate).isLessThanOrEqualTo(d("0.01"));
        }
    }

    @Test
    @DisplayName("GST 2.0: 28% is a rate on 21 Sep 2025 and refused on 22 Sep")
    void rateMustBeASlabOnTheDate() {
        GstCalculator.requireValid(TaxCategory.TAXABLE, d("28"), null, LocalDate.of(2025, 9, 21), RULES);
        assertThatThrownBy(() -> GstCalculator.requireValid(TaxCategory.TAXABLE, d("28"), null,
                LocalDate.of(2025, 9, 22), RULES)).hasMessageContaining("28% is not a GST rate on 2025-09-22");
        assertThatThrownBy(() -> GstCalculator.requireValid(TaxCategory.TAXABLE, d("7"), null,
                LocalDate.of(2026, 1, 1), RULES)).hasMessageContaining("not a GST rate");
    }

    @Test
    @DisplayName("a category is required, taxable needs a rate above zero, untaxed lines carry none")
    void categoryRules() {
        LocalDate on = LocalDate.of(2026, 1, 1);
        assertThatThrownBy(() -> GstCalculator.requireValid(null, d("18"), null, on, RULES))
                .hasMessageContaining("needs a GST category");
        assertThatThrownBy(() -> GstCalculator.requireValid(TaxCategory.TAXABLE, d("0"), null, on, RULES))
                .hasMessageContaining("nil-rated");
        assertThatThrownBy(() -> GstCalculator.requireValid(TaxCategory.EXEMPT, d("5"), null, on, RULES))
                .hasMessageContaining("exempt line carries no GST");
        assertThatThrownBy(() -> GstCalculator.requireValid(TaxCategory.NIL_RATED, d("0"), d("1"), on, RULES))
                .hasMessageContaining("carries no GST or cess");
        assertThatThrownBy(() -> GstCalculator.requireValid(TaxCategory.TAXABLE, d("18"), d("-1"), on, RULES))
                .hasMessageContaining("negative");
        GstCalculator.requireValid(TaxCategory.NON_GST, null, null, on, RULES);
    }
}
