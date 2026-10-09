package com.accubook.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RoundOffTest {

    @ParameterizedTest(name = "{0} -> {1} ({2})")
    @CsvSource({
            "99.60, 100.00, 0.40",
            "99.49, 99.00, -0.49",
            "99.50, 100.00, 0.50",
            "100.00, 100.00, 0.00"})
    @DisplayName("P7-10: to the nearest rupee, half-up")
    void nearestRupee(String amount, String total, String roundOff) {
        RoundOff money = RoundOff.of(new BigDecimal(amount), true);
        assertThat(money.total()).isEqualByComparingTo(total);
        assertThat(money.roundOff()).isEqualByComparingTo(roundOff);
        assertThat(money.amount().add(money.roundOff())).isEqualByComparingTo(money.total());
    }

    @ParameterizedTest
    @CsvSource({"99.60", "0.01"})
    @DisplayName("switched off, the total is the amount")
    void disabled(String amount) {
        RoundOff money = RoundOff.of(new BigDecimal(amount), false);
        assertThat(money.total()).isEqualByComparingTo(amount);
        assertThat(money.roundOff()).isZero();
    }
}
