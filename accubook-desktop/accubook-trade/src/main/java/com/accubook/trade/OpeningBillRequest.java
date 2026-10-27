package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * An unpaid bill from before the books begin (S2-4).
 *
 * @param number   as the bill was numbered at the time - the party's own number for a vendor's bill
 * @param amount   what is still owed on it, in whole paise
 * @param dueDate  null for due on the bill date
 */
public record OpeningBillRequest(String partyId, String number, LocalDate billDate, LocalDate dueDate,
                                 BigDecimal amount, String narration) {

    public OpeningBillRequest {
        Objects.requireNonNull(partyId, "partyId");
        if (number == null || number.isBlank()) {
            throw new TradeException("An opening bill needs its number");
        }
        Objects.requireNonNull(billDate, "billDate");
        if (dueDate != null && dueDate.isBefore(billDate)) {
            throw new TradeException("A bill cannot fall due before its own date");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new TradeException("An opening bill is for a positive amount");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new TradeException("An opening bill is in whole paise");
        }
        number = number.strip();
    }
}
