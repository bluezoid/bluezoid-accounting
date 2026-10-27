package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An opening bill as recorded (S2-4); {@code outstanding} is what is still owed on it today. */
public record OpeningBill(String id, String partyId, String number, LocalDate billDate, LocalDate dueDate,
                          BigDecimal amount, BigDecimal outstanding, boolean cancelled, String voucherId) {
}
