package com.accubook.accounting.chart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrialBalanceQueryTest {

    private static final FiscalYear FY25 = new FiscalYear("a", "2025-26",
            LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), false);
    private static final FiscalYear FY26 = new FiscalYear("b", "2026-27",
            LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), false);

    @Test
    @DisplayName("the report opens on the year containing today")
    void opensOnCurrentYear() {
        assertEquals(FY25, TrialBalanceQuery.defaultYear(List.of(FY26, FY25), LocalDate.of(2026, 3, 31)).orElseThrow());
        assertEquals(FY26, TrialBalanceQuery.defaultYear(List.of(FY26, FY25), LocalDate.of(2026, 4, 1)).orElseThrow());
    }

    @Test
    @DisplayName("after the newest year has ended, the report opens on the newest year rather than nothing")
    void fallsBackToNewest() {
        assertEquals(FY26, TrialBalanceQuery.defaultYear(List.of(FY26, FY25), LocalDate.of(2028, 1, 1)).orElseThrow());
    }

    @Test
    @DisplayName("a company with no fiscal year has no default")
    void noYears() {
        assertTrue(TrialBalanceQuery.defaultYear(List.of(), LocalDate.of(2026, 1, 1)).isEmpty());
    }
}
