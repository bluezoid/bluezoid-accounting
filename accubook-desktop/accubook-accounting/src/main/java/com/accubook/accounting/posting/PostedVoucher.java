package com.accubook.accounting.posting;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The outcome of a successful posting.
 *
 * <p>{@code replayed} distinguishes a voucher created now from one returned
 * because an idempotency key matched an earlier commit. The caller usually does
 * not care - both mean "your voucher exists" - but a scheduler logging its work
 * does, and a UI can tell the user their retry was already applied rather than
 * silently doing nothing.
 */
public record PostedVoucher(
        String voucherId,
        String voucherNumber,
        LocalDate voucherDate,
        BigDecimal total,
        VoucherStatus status,
        boolean replayed) {
}
