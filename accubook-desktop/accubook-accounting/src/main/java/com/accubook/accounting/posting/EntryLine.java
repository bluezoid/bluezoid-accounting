package com.accubook.accounting.posting;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of a voucher: an amount against a ledger, on exactly one side.
 *
 * <p>Debit and credit are separate fields rather than one signed amount, because
 * that is how an accountant reads a voucher and how the entries are stored. A
 * single signed number would make "which side" a matter of interpretation, and
 * the reference application demonstrates where that ends - it writes
 * DEBIT/CREDIT and reads DR/CR, so every user-entered opening balance is
 * sign-flipped.
 *
 * <p>Exactly one side must be positive. A line with both, or neither, is not a
 * journal line and is refused at construction.
 */
public record EntryLine(String ledgerId, BigDecimal debit, BigDecimal credit, String narration) {

    public EntryLine {
        Objects.requireNonNull(ledgerId, "ledgerId");
        Objects.requireNonNull(debit, "debit");
        Objects.requireNonNull(credit, "credit");
        if (ledgerId.isBlank()) {
            throw new IllegalArgumentException("ledgerId must not be blank");
        }
        if (debit.signum() < 0 || credit.signum() < 0) {
            throw new IllegalArgumentException(
                    "Amounts must not be negative; use the other side instead: "
                            + "debit=" + debit + " credit=" + credit);
        }
        boolean hasDebit = debit.signum() > 0;
        boolean hasCredit = credit.signum() > 0;
        if (hasDebit == hasCredit) {
            throw new IllegalArgumentException(hasDebit
                    ? "An entry cannot be both debit and credit: " + debit + " / " + credit
                    : "An entry must have a non-zero debit or credit");
        }
    }

    /** A debit line. */
    public static EntryLine debit(String ledgerId, BigDecimal amount) {
        return new EntryLine(ledgerId, amount, BigDecimal.ZERO, null);
    }

    /** A credit line. */
    public static EntryLine credit(String ledgerId, BigDecimal amount) {
        return new EntryLine(ledgerId, BigDecimal.ZERO, amount, null);
    }

    /** This line with the sides exchanged, for reversal. */
    public EntryLine reversed() {
        return new EntryLine(ledgerId, credit, debit, narration);
    }

    public boolean isDebit() {
        return debit.signum() > 0;
    }
}
