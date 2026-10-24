package com.accubook.accounting.year;

import java.math.BigDecimal;

/**
 * The opening voucher posted (P11-1).
 *
 * @param difference what the balances given did not balance by, put to Opening
 *                   Balance Equity: debits less credits; zero when they balanced
 */
public record OpeningResult(String voucherId, BigDecimal difference) {

    public boolean balanced() {
        return difference.signum() == 0;
    }
}
