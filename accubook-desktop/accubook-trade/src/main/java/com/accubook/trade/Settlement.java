package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A receipt or payment as stored, with how much of it is still unallocated.
 *
 * @param tdsDeducted TDS the customer deducted; the bank received {@code amount - tdsDeducted}
 */
public record Settlement(String id, String number, String partyId, LocalDate date, BigDecimal amount,
                         String accountLedgerId, PaymentMode mode, String referenceNo, boolean cancelled,
                         String voucherId, BigDecimal unallocated, BigDecimal tdsDeducted, String tdsSection) {
}
