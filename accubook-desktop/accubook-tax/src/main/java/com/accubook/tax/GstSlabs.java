package com.accubook.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The GST rates permitted between two dates - e.g. 12% and 28% end on 21 Sep 2025, 40% begins on 22 Sep 2025. */
public record GstSlabs(LocalDate from, LocalDate to, List<BigDecimal> rates) {

    public GstSlabs {
        rates = List.copyOf(rates);
    }

    public boolean appliesOn(LocalDate date) {
        return !date.isBefore(from) && (to == null || !date.isAfter(to));
    }

    public boolean permits(BigDecimal rate) {
        return rates.stream().anyMatch(r -> r.compareTo(rate) == 0);
    }
}
