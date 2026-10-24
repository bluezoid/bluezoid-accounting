package com.accubook.banking;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A line of a bank statement, as read.
 *
 * @param withdrawal money out of the account, else zero
 * @param deposit    money into the account, else zero
 * @param balance    the running balance after the line, or null if the file has none
 */
public record StatementLine(int row, LocalDate date, String narration, String reference, BigDecimal withdrawal,
                            BigDecimal deposit, BigDecimal balance) {

    /** Into the account positive, out negative. */
    public BigDecimal net() {
        return deposit.subtract(withdrawal);
    }
}
