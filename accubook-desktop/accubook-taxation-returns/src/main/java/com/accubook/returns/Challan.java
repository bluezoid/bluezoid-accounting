package com.accubook.returns;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A recorded deposit of TDS or TCS. */
public record Challan(String id, String kind, String section, LocalDate depositDate, String bsrCode,
                      String challanSerial, BigDecimal tax, BigDecimal interest, BigDecimal fee, String voucherId) {
}
