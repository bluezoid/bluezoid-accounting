package com.accubook.accounting.chart;

import java.time.LocalDate;

/** A fiscal year as recorded in the company file - never derived from a calendar rule. */
public record FiscalYear(String id, String name, LocalDate startDate, LocalDate endDate, boolean closed) {

    /** Whether {@code date} falls within this year, both ends inclusive. */
    public boolean contains(LocalDate date) {
        return !date.isBefore(startDate) && !date.isAfter(endDate);
    }
}
