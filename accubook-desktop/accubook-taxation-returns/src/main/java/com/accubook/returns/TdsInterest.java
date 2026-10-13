package com.accubook.returns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;

/**
 * When deducted TDS is due, and the interest on depositing it late (P9-23).
 *
 * <p>Due: the 7th of the next month; 30 April for March (rule 30). Late:
 * s.201(1A)(ii), 1.5% for every month or part of a month from the date of
 * deduction to the date of deposit. Months are counted as calendar months,
 * the first and last included, as TRACES computes them - UNVERIFIED against a
 * CA's reading (V-1).
 */
public final class TdsInterest {

    private static final BigDecimal RATE = new BigDecimal("1.5");

    private TdsInterest() {
    }

    public static LocalDate dueDate(LocalDate deducted) {
        LocalDate next = deducted.plusMonths(1);
        return deducted.getMonth() == Month.MARCH ? LocalDate.of(deducted.getYear(), 4, 30) : next.withDayOfMonth(7);
    }

    /** Months or part months charged: zero when deposited by the due date. */
    public static int months(LocalDate deducted, LocalDate deposited) {
        if (!deposited.isAfter(dueDate(deducted))) {
            return 0;
        }
        return (deposited.getYear() * 12 + deposited.getMonthValue())
                - (deducted.getYear() * 12 + deducted.getMonthValue()) + 1;
    }

    public static BigDecimal interest(BigDecimal amount, LocalDate deducted, LocalDate deposited) {
        return amount.multiply(RATE).multiply(BigDecimal.valueOf(months(deducted, deposited)))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
