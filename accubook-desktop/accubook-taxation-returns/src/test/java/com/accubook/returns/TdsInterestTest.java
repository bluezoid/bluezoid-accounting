package com.accubook.returns;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** P9-23: when TDS is due, and s.201(1A) interest when it is late. */
class TdsInterestTest {

    @Test
    @DisplayName("due on the 7th of the next month; March's on 30 April")
    void dueDates() {
        assertThat(TdsInterest.dueDate(LocalDate.of(2026, 5, 31))).isEqualTo(LocalDate.of(2026, 6, 7));
        assertThat(TdsInterest.dueDate(LocalDate.of(2026, 12, 3))).isEqualTo(LocalDate.of(2027, 1, 7));
        assertThat(TdsInterest.dueDate(LocalDate.of(2027, 3, 15))).isEqualTo(LocalDate.of(2027, 4, 30));
    }

    @Test
    @DisplayName("deposited by the due date: no interest")
    void onTime() {
        assertThat(TdsInterest.interest(new BigDecimal("1000"), LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 7)))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("late: 1.5% a month or part, the month of deduction and of deposit both counted")
    void late() {
        // Deducted 31 Jan, deposited 8 Mar: Jan, Feb, Mar = 3 months -> 4.5% of 2,000 = 90.
        assertThat(TdsInterest.months(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 3, 8))).isEqualTo(3);
        assertThat(TdsInterest.interest(new BigDecimal("2000"), LocalDate.of(2027, 1, 31), LocalDate.of(2027, 3, 8)))
                .isEqualByComparingTo("90.00");
        // One day late, 8 June for May: May and June = 2 months.
        assertThat(TdsInterest.months(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 6, 8))).isEqualTo(2);
    }
}
