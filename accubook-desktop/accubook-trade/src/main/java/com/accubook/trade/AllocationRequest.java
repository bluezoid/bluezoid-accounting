package com.accubook.trade;

import java.math.BigDecimal;
import java.util.Objects;

/** Apply part of a receipt, payment or note to one invoice or bill. */
public record AllocationRequest(String documentId, BigDecimal amount) {

    public AllocationRequest {
        Objects.requireNonNull(documentId, "documentId");
        if (amount == null || amount.signum() <= 0) {
            throw new TradeException("An allocation must be a positive amount");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new TradeException("An allocation is in whole paise");
        }
    }
}
