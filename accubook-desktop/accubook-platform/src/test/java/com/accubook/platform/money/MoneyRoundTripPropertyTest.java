package com.accubook.platform.money;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based verification of the money boundary.
 *
 * <p>Example-based tests confirm the cases someone thought of. For the one
 * conversion that every monetary value in the product passes through, that is
 * not enough - so these properties assert exactness across the whole input
 * range, including the boundaries and negatives where scale bugs actually live.
 *
 * <p>This is a Phase 1 exit criterion.
 */
class MoneyRoundTripPropertyTest {

    @Property(tries = 2000)
    void anyStoredValueRoundTripsExactly(
            @ForAll @LongRange(min = -1_000_000_000_000_000L, max = 1_000_000_000_000_000L) long stored,
            @ForAll Scale scale) {

        BigDecimal value = MoneyMapper.fromStored(stored, scale);
        assertThat(MoneyMapper.toStored(value, scale)).isEqualTo(stored);
    }

    @Property(tries = 2000)
    void additionInStoredFormMatchesBigDecimalAddition(
            @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long a,
            @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long b) {

        BigDecimal viaStored = MoneyMapper.fromStored(a + b, Scale.MONEY);
        BigDecimal viaBigDecimal = MoneyMapper.fromStored(a, Scale.MONEY)
                .add(MoneyMapper.fromStored(b, Scale.MONEY));

        // Integer arithmetic on minor units must agree with BigDecimal exactly.
        // This is what makes SQL SUM() over the stored column trustworthy.
        assertThat(viaStored).isEqualByComparingTo(viaBigDecimal);
    }

    @Property(tries = 500)
    void summingManyValuesStaysExact(
            @ForAll @IntRange(min = 1, max = 500) int count,
            @ForAll @LongRange(min = -1_000_000L, max = 1_000_000L) long each) {

        long total = 0;
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < count; i++) {
            total += each;
            running = running.add(MoneyMapper.fromStored(each, Scale.MONEY));
        }
        assertThat(MoneyMapper.fromStored(total, Scale.MONEY)).isEqualByComparingTo(running);
    }

    @Property(tries = 1000)
    void negationIsSymmetric(
            @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long stored,
            @ForAll Scale scale) {

        BigDecimal value = MoneyMapper.fromStored(stored, scale);
        assertThat(MoneyMapper.toStored(value.negate(), scale)).isEqualTo(-stored);
    }

    @Property(tries = 1000)
    void orderingIsPreservedAcrossTheBoundary(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long a,
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long b) {

        // SQLite orders the stored INTEGER column. If ordering did not survive
        // the boundary, ORDER BY and range queries would be wrong - which is
        // precisely how TEXT decimal storage fails.
        int storedOrder = Long.compare(a, b);
        int decimalOrder = MoneyMapper.fromStored(a, Scale.MONEY)
                .compareTo(MoneyMapper.fromStored(b, Scale.MONEY));
        assertThat(Integer.signum(decimalOrder)).isEqualTo(Integer.signum(storedOrder));
    }
}
