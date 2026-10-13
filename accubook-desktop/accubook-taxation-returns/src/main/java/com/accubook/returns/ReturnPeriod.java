package com.accubook.returns;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * A GST return period: a calendar month, a quarter under QRMP, or the
 * financial year (GSTR-9). Quarters follow the financial year (April-June,
 * ...), so a period never spans two financial years or two document number
 * series.
 */
public record ReturnPeriod(LocalDate from, LocalDate to) {

    public ReturnPeriod {
        YearMonth start = YearMonth.from(from);
        boolean month = to.equals(start.atEndOfMonth());
        boolean quarter = start.getMonthValue() % 3 == 1 && to.equals(start.plusMonths(2).atEndOfMonth());
        boolean year = start.getMonthValue() == 4 && to.equals(start.plusMonths(11).atEndOfMonth());
        if (from.getDayOfMonth() != 1 || !(month || quarter || year)) {
            throw new IllegalArgumentException("A return period is a calendar month, a quarter starting in April, "
                    + "July, October or January, or a financial year");
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

    /** The financial year starting in April of {@code startYear}. */
    public static ReturnPeriod financialYear(int startYear) {
        return new ReturnPeriod(LocalDate.of(startYear, 4, 1), LocalDate.of(startYear + 1, 3, 31));
    }

    public boolean isQuarter() {
        return from.plusMonths(3).minusDays(1).equals(to);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** The portal's "fp": MMYYYY of the period's last month. */
    public String fp() {
        return to.format(DateTimeFormatter.ofPattern("MMyyyy"));
    }
}
