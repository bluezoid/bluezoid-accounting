package com.accubook.ui.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Dates typed day first, the year filled from the open fiscal year. */
class DateInputTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private static final LocalDate FY_START = LocalDate.of(2026, 4, 1);

    @ParameterizedTest
    @CsvSource({
            "15-04-2026,2026-04-15", "15/4/26,2026-04-15", "15.4.2026,2026-04-15",
            "15-4,2026-04-15", "15-1,2027-01-15", "31-3,2027-03-31", "1-4,2026-04-01",
            "5,2026-10-05", "t,2026-10-24", "T,2026-10-24", "29-02-2028,2028-02-29"})
    @DisplayName("day first; a missing year comes from the fiscal year, a missing month from today")
    void reads(String typed, LocalDate expected) {
        assertEquals(expected, DateInput.parse(typed, TODAY, FY_START));
    }

    @Test
    @DisplayName("a calendar-year company fills the year from January")
    void calendarYear() {
        assertEquals(LocalDate.of(2026, 3, 15), DateInput.parse("15-3", TODAY, LocalDate.of(2026, 1, 1)));
    }

    @Test
    @DisplayName("blank is no date; shown as dd-mm-yyyy")
    void blankAndFormat() {
        assertNull(DateInput.parse(" ", TODAY, FY_START));
        assertEquals("05-04-2026", DateInput.format(LocalDate.of(2026, 4, 5)));
        assertEquals("", DateInput.format(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"31-02-2026", "29-02-2026", "0-4", "15-13", "2026-04-15", "15-04-202", "yesterday", "15 4"})
    @DisplayName("anything else is refused")
    void refuses(String typed) {
        assertThrows(IllegalArgumentException.class, () -> DateInput.parse(typed, TODAY, FY_START));
    }
}
