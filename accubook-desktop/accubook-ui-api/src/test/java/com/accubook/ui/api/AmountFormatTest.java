package com.accubook.ui.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmountFormatTest {

    @ParameterizedTest
    @CsvSource({
            "0, 0.00",
            "5, 5.00",
            "999.5, 999.50",
            "1000, '1,000.00'",
            "100000, '1,00,000.00'",
            "1234567.89, '12,34,567.89'",
            "123456789, '12,34,56,789.00'",
            "-250000, '-2,50,000.00'",
            "10.1234, 10.1234",
            "10.1200, 10.12"})
    @DisplayName("lakh/crore grouping, at least two decimals, never rounded")
    void formats(String value, String expected) {
        assertEquals(expected, AmountFormat.format(new BigDecimal(value)));
    }
}
