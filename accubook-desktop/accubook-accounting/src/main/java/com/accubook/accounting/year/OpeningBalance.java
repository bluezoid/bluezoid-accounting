package com.accubook.accounting.year;

import java.math.BigDecimal;
import java.util.Objects;

/** A ledger's balance when the books start in AccuBook: a debit or a credit, not both. */
public record OpeningBalance(String ledgerId, BigDecimal debit, BigDecimal credit) {

    public OpeningBalance {
        Objects.requireNonNull(ledgerId, "ledgerId");
        debit = debit == null ? BigDecimal.ZERO : debit;
        credit = credit == null ? BigDecimal.ZERO : credit;
        if (debit.signum() < 0 || credit.signum() < 0 || (debit.signum() > 0 && credit.signum() > 0)) {
            throw new YearEndException("An opening balance is a debit or a credit, not negative and not both");
        }
    }

    public static OpeningBalance debit(String ledgerId, BigDecimal amount) {
        return new OpeningBalance(ledgerId, amount, null);
    }

    public static OpeningBalance credit(String ledgerId, BigDecimal amount) {
        return new OpeningBalance(ledgerId, null, amount);
    }
}
