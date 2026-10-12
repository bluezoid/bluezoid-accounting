package com.accubook.returns;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * A GST return period: a calendar month, or a quarter under QRMP. Quarters
 * follow the financial year (April-June, ...), so a period never spans two
 * financial years or two document number series.
 */
public record ReturnPeriod(LocalDate from, LocalDate to) {

    public ReturnPeriod {
        YearMonth start = YearMonth.from(from);
        boolean month = to.equals(start.atEndOfMonth());
        boolean quarter = start.getMonthValue() % 3 == 1 && to.equals(start.plusMonths(2).atEndOfMonth());
        if (from.getDayOfMonth() != 1 || !(month || quarter)) {
            throw new IllegalArgumentException("A return period is a calendar month or a quarter starting in April, "
                    + "July, October or January");
        }
    }

    public static ReturnPeriod month(int year, int month) {
        YearMonth m = YearMonth.of(year, month);
        return new ReturnPeriod(m.atDay(1), m.atEndOfMonth());
    }

    /** The quarter starting in {@code firstMonth} (4, 7, 10 or 1). */
    public static ReturnPeriod quarter(int year, int firstMonth) {
        YearMonth m = YearMonth.of(year, firstMonth);
        return new ReturnPeriod(m.atDay(1), m.plusMonths(2).atEndOfMonth());
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** The portal's "fp": MMYYYY of the period's last month. */
    public String fp() {
        return to.format(DateTimeFormatter.ofPattern("MMyyyy"));
    }
}
