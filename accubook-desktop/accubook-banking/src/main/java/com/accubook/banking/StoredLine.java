package com.accubook.banking;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An imported statement line, with whether it is matched. */
public record StoredLine(String id, LocalDate date, String narration, String reference, BigDecimal withdrawal,
                         BigDecimal deposit, BigDecimal balance, boolean matched) {

    public BigDecimal net() {
        return deposit.subtract(withdrawal);
    }
}
