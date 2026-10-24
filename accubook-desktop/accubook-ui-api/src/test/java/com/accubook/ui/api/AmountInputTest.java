package com.accubook.ui.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a person types into an amount field, read exactly or refused - never rounded. */
class AmountInputTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "1,23,456.78|123456.78", "123,456.78|123456.78", "500|500", "₹ 500.50|500.50", "Rs. 75|75",
            ".5|0.5", "10.|10", " 42 |42", "0|0"})
    @DisplayName("Indian or western grouping, a rupee sign, spaces")
    void reads(String typed, String expected) {
        BigDecimal value = AmountInput.money().parse(typed);
        assertEquals(new BigDecimal(expected), value);
        assertEquals(new BigDecimal(expected).scale(), value.scale());
    }

    @Test
    @DisplayName("blank is no value")
    void blank() {
        assertNull(AmountInput.money().parse("  "));
        assertNull(AmountInput.money().parse(null));
    }

    @Test
    @DisplayName("a third decimal place in money is refused, not rounded")
    void neverRounds() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AmountInput.money().parse("10.005"));
        assertTrue(e.getMessage().contains("2 decimal places"));
        assertEquals(new BigDecimal("10.005"), AmountInput.ofScale(4).parse("10.005"));
    }

    @Test
    @DisplayName("negative only where the field allows it")
    void negative() {
        assertThrows(IllegalArgumentException.class, () -> AmountInput.money().parse("-5"));
        assertEquals(new BigDecimal("-1234.50"), AmountInput.signedMoney().parse("-1,234.50"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12a", "1.2.3", "1,,", "1,.5", ",5", "--5", "1e5", "five", "1 000"})
    @DisplayName("anything else is not an amount")
    void refuses(String typed) {
        assertThrows(IllegalArgumentException.class, () -> AmountInput.signedMoney().parse(typed));
    }
}
