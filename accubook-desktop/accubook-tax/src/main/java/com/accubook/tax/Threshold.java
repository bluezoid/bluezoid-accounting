package com.accubook.tax;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A named monetary limit in force between two dates (to null = still in force). */
public record Threshold(String key, LocalDate from, LocalDate to, BigDecimal value) {

    public boolean appliesOn(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }
}
