package com.accubook.platform.money;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("MoneyMapper")
class MoneyMapperTest {

    @Test
    @DisplayName("stores money at scale 4")
    void storesMoneyAtScaleFour() {
        assertThat(MoneyMapper.toStored(new BigDecimal("123.45"), Scale.MONEY))
                .isEqualTo(1_234_500L);
    }

    @Test
    @DisplayName("stores a basis-point rate: 18% -> 1800")
    void storesRateAsBasisPoints() {
        assertThat(MoneyMapper.toStored(new BigDecimal("18.00"), Scale.RATE))
                .isEqualTo(1_800L);
    }

    @Test
    @DisplayName("stores a GST rate to four places: 18% -> 180000, the 0.125% half of 0.25% -> 1250")
    void storesTaxRateAtScaleFour() {
        assertThat(MoneyMapper.toStored(new BigDecimal("18"), Scale.TAX_RATE)).isEqualTo(180_000L);
        assertThat(MoneyMapper.toStored(new BigDecimal("0.125"), Scale.TAX_RATE)).isEqualTo(1_250L);
    }

    @Test
    @DisplayName("stores an FX rate at scale 8")
    void storesFxAtScaleEight() {
        assertThat(MoneyMapper.toStored(new BigDecimal("83.12345678"), Scale.FX))
                .isEqualTo(8_312_345_678L);
    }

    @Test
    @DisplayName("stores a quantity at scale 4, refusing finer precision")
    void storesQuantityAtScaleFour() {
        assertThat(MoneyMapper.toStored(new BigDecimal("2.5"), Scale.QUANTITY))
                .isEqualTo(25_000L);
        assertThatThrownBy(() -> MoneyMapper.toStored(new BigDecimal("0.00001"), Scale.QUANTITY))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("round-trips a value unchanged")
    void roundTrips() {
        BigDecimal original = new BigDecimal("1234.5678");
        long stored = MoneyMapper.toStored(original, Scale.MONEY);
        assertThat(MoneyMapper.fromStored(stored, Scale.MONEY))
                .isEqualByComparingTo(original);
    }

    @Test
    @DisplayName("rejects a value with more precision than the column holds")
    void rejectsExcessPrecision() {
        // The reason toStored uses longValueExact: silently truncating here
        // would lose money with no error anywhere.
        assertThatThrownBy(() -> MoneyMapper.toStored(new BigDecimal("1.23456"), Scale.MONEY))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("rejects a value too large for the column")
    void rejectsOverflow() {
        BigDecimal tooBig = MoneyMapper.maxValue(Scale.MONEY).add(BigDecimal.ONE);
        assertThatThrownBy(() -> MoneyMapper.toStored(tooBig, Scale.MONEY))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("handles negative amounts")
    void handlesNegatives() {
        assertThat(MoneyMapper.toStored(new BigDecimal("-500.25"), Scale.MONEY))
                .isEqualTo(-5_002_500L);
        assertThat(MoneyMapper.fromStored(-5_002_500L, Scale.MONEY))
                .isEqualByComparingTo(new BigDecimal("-500.25"));
    }

    @Test
    @DisplayName("money scale reaches beyond any realistic book")
    void moneyRangeIsAmple() {
        // ~Rs 922 lakh crore, about 3x India's GDP.
        assertThat(MoneyMapper.maxValue(Scale.MONEY))
                .isGreaterThan(new BigDecimal("900000000000000"));
    }

    @Test
    @DisplayName("0.1 + 0.2 == 0.3 exactly, which SQLite REAL cannot do")
    void exactDecimalAddition() {
        long a = MoneyMapper.toStored(new BigDecimal("0.1"), Scale.MONEY);
        long b = MoneyMapper.toStored(new BigDecimal("0.2"), Scale.MONEY);
        assertThat(MoneyMapper.fromStored(a + b, Scale.MONEY))
                .isEqualByComparingTo(new BigDecimal("0.3"));
    }

    @Test
    @DisplayName("ten thousand additions of 0.01 sum to exactly 100")
    void longAdditionChainStaysExact() {
        long total = 0;
        long cent = MoneyMapper.toStored(new BigDecimal("0.01"), Scale.MONEY);
        for (int i = 0; i < 10_000; i++) {
            total += cent;
        }
        assertThat(MoneyMapper.fromStored(total, Scale.MONEY))
                .isEqualByComparingTo(new BigDecimal("100"));
    }

    @ParameterizedTest
    @EnumSource(Scale.class)
    @DisplayName("every scale round-trips zero")
    void everyScaleRoundTripsZero(Scale scale) {
        assertThat(MoneyMapper.fromStored(MoneyMapper.toStored(BigDecimal.ZERO, scale), scale))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("applies a percentage rate without SQL-side overflow")
    void appliesRate() {
        // 1,00,000.00 at 18% = 18,000.00
        assertThat(MoneyMapper.applyRate(new BigDecimal("100000.00"), new BigDecimal("18.00")))
                .isEqualByComparingTo(new BigDecimal("18000"));
    }

    @Test
    @DisplayName("applyRate does not round, so sum-then-round stays possible")
    void applyRateDoesNotRound() {
        // 1234.56 @ 5% = 61.728 - three decimals, matching the reference's
        // unrounded intermediate. Rounding here would change every GST total.
        assertThat(MoneyMapper.applyRate(new BigDecimal("1234.56"), new BigDecimal("5.00")))
                .isEqualByComparingTo(new BigDecimal("61.728"));
    }

    @Test
    @DisplayName("converts using an FX rate")
    void convertsFx() {
        assertThat(MoneyMapper.convert(new BigDecimal("100.00"), new BigDecimal("83.12345678")))
                .isEqualByComparingTo(new BigDecimal("8312.345678"));
    }

    @Test
    @DisplayName("rounding requires an explicit mode")
    void roundingIsExplicit() {
        assertThat(MoneyMapper.round(new BigDecimal("1.005"), Scale.RATE, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("1.01"));
        assertThat(MoneyMapper.round(new BigDecimal("1.005"), Scale.RATE, RoundingMode.HALF_EVEN))
                .isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    @DisplayName("rejects nulls rather than defaulting")
    void rejectsNulls() {
        assertThatNullPointerException()
                .isThrownBy(() -> MoneyMapper.toStored(null, Scale.MONEY));
        assertThatNullPointerException()
                .isThrownBy(() -> MoneyMapper.toStored(BigDecimal.ONE, null));
    }

    @Test
    @DisplayName("each scale declares its column suffix")
    void scalesDeclareSuffixes() {
        assertThat(Scale.MONEY.columnSuffix()).isEqualTo("_e4");
        assertThat(Scale.RATE.columnSuffix()).isEqualTo("_bp");
        assertThat(Scale.FX.columnSuffix()).isEqualTo("_e8");
    }
}
