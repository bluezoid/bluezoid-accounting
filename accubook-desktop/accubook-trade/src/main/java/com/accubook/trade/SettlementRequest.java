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
 * @param tdsDeducted     receipts only: TDS the customer deducted (P9-10). The
 *                        receipt settles {@code amount}; the bank gets
 *                        {@code amount - tdsDeducted}
 * @param tdsSection      the section the customer deducted under, with {@code tdsDeducted}
 */
public record SettlementRequest(String partyId, LocalDate date, BigDecimal amount, String accountLedgerId,
                                PaymentMode mode, String referenceNo, LocalDate instrumentDate, String narration,
                                List<AllocationRequest> allocations, BigDecimal tdsDeducted, String tdsSection) {

    public SettlementRequest(String partyId, LocalDate date, BigDecimal amount, String accountLedgerId,
                             PaymentMode mode, String referenceNo, LocalDate instrumentDate, String narration,
                             List<AllocationRequest> allocations) {
        this(partyId, date, amount, accountLedgerId, mode, referenceNo, instrumentDate, narration, allocations,
                BigDecimal.ZERO, null);
    }

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
        tdsDeducted = tdsDeducted == null ? BigDecimal.ZERO : tdsDeducted;
        if (tdsDeducted.signum() < 0 || tdsDeducted.stripTrailingZeros().scale() > 2) {
            throw new TradeException("TDS deducted is a non-negative amount in whole paise");
        }
        if (tdsDeducted.compareTo(amount) >= 0) {
            throw new TradeException("TDS deducted must be less than the amount it was deducted from");
        }
        if ((tdsDeducted.signum() > 0) != (tdsSection != null && !tdsSection.isBlank())) {
            throw new TradeException("TDS deducted needs its section, and a section needs an amount");
        }
    }

    /** This receipt with TDS the customer deducted under {@code section}. */
    public SettlementRequest withTds(BigDecimal deducted, String section) {
        return new SettlementRequest(partyId, date, amount, accountLedgerId, mode, referenceNo, instrumentDate,
                narration, allocations, deducted, section);
    }

    public static SettlementRequest of(String partyId, LocalDate date, BigDecimal amount, String accountLedgerId,
                                       PaymentMode mode, List<AllocationRequest> allocations) {
        return new SettlementRequest(partyId, date, amount, accountLedgerId, mode, null, null, null, allocations);
    }
}
