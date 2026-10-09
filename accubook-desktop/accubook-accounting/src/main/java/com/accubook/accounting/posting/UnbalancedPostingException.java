package com.accubook.accounting.posting;

import java.math.BigDecimal;

/**
 * Debits did not equal credits.
 *
 * <p>The comparison is exact. The reference application gates its main journal
 * path with a Rs 0.01 epsilon ({@code closeEnough}, {@code money.ts:26}) while
 * its posting core compares exactly - so the looser check runs first and decides
 * what reaches the stricter one. At {@link com.accubook.platform.money.Scale}
 * MONEY every amount is a scaled integer, so there is no rounding for an epsilon
 * to absorb and no reason to permit one.
 */
public class UnbalancedPostingException extends PostingException {

    private final BigDecimal totalDebit;
    private final BigDecimal totalCredit;

    public UnbalancedPostingException(BigDecimal totalDebit, BigDecimal totalCredit) {
        super("Voucher does not balance: debits " + totalDebit
                + " != credits " + totalCredit
                + " (difference " + totalDebit.subtract(totalCredit).abs() + ")");
        this.totalDebit = totalDebit;
        this.totalCredit = totalCredit;
    }

    public BigDecimal totalDebit() {
        return totalDebit;
    }

    public BigDecimal totalCredit() {
        return totalCredit;
    }
}
