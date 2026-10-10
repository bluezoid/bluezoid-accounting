package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Money received from a customer or paid to a vendor (P7-14: posted on entry).
 *
 * @param accountLedgerId a ledger in the Cash &amp; Bank group (P7-16)
 * @param allocations     which documents it pays; the rest is an on-account
 *                        advance, allocatable later
 */
public record SettlementRequest(String partyId, LocalDate date, BigDecimal amount, String accountLedgerId,
                                PaymentMode mode, String referenceNo, LocalDate instrumentDate, String narration,
                                List<AllocationRequest> allocations) {

    public SettlementRequest {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(accountLedgerId, "accountLedgerId");
        Objects.requireNonNull(mode, "mode");
        if (amount == null || amount.signum() <= 0) {
            throw new TradeException("A receipt or payment must be a positive amount");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new TradeException("Money is recorded in whole paise");
        }
        allocations = allocations == null ? List.of() : List.copyOf(allocations);
    }

    public static SettlementRequest of(String partyId, LocalDate date, BigDecimal amount, String accountLedgerId,
                                       PaymentMode mode, List<AllocationRequest> allocations) {
        return new SettlementRequest(partyId, date, amount, accountLedgerId, mode, null, null, null, allocations);
    }
}
