package com.accubook.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineMathTest {

    @ParameterizedTest(name = "{0} x {1} less {2}bp = {3}")
    @CsvSource({
            "1, 100, 0, 100.00",
            "3, 33.333, 0, 100.00",       // 99.999 rounds half-up to 100.00
            "2.5, 41.6667, 0, 104.17",    // 104.16675
            "10, 99.99, 1000, 899.91",    // 10% off 999.90
            "1, 0.005, 0, 0.01",          // half a paisa rounds up
            "1, 0.0049, 0, 0.00",
            "7, 13, 10000, 0.00",         // a free line
            "1.5, 120, 1250, 157.50"})
    @DisplayName("P7-9: rounded once, to paise, half-up")
    void amounts(String quantity, String rate, int discountBp, String expected) {
        assertThat(LineMath.amount(new BigDecimal(quantity), new BigDecimal(rate), discountBp))
                .isEqualByComparingTo(expected)
                .hasScaleOf(2);
    }

    @Test
    @DisplayName("rounding the difference once, not the gross and the discount separately")
    void singleRounding() {
        // gross 1.005 -> 1.01, discount 50% of 1.005 = 0.5025 -> 0.50, difference 0.51;
        // exact 0.5025 -> 0.50. Separate rounding would print 0.51.
        assertThat(LineMath.amount(new BigDecimal("1"), new BigDecimal("1.005"), 5000))
                .isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("refuses a zero quantity, a negative rate, an over-precise rate and a discount outside 0-100%")
    void refusals() {
        assertThatThrownBy(() -> LineMath.amount(BigDecimal.ZERO, BigDecimal.ONE, 0)).isInstanceOf(TradeException.class);
        assertThatThrownBy(() -> LineMath.amount(BigDecimal.ONE, new BigDecimal("-1"), 0)).isInstanceOf(TradeException.class);
        assertThatThrownBy(() -> LineMath.amount(BigDecimal.ONE, new BigDecimal("1.00001"), 0)).isInstanceOf(TradeException.class);
        assertThatThrownBy(() -> LineMath.amount(BigDecimal.ONE, BigDecimal.ONE, 10001)).isInstanceOf(TradeException.class);
    }
}
