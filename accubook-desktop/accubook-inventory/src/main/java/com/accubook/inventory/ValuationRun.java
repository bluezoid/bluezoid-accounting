package com.accubook.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A posted closing-stock valuation (P8-4).
 *
 * <p>STALE: an entry dated on or before {@code date} was recorded afterwards, so
 * the stock value it posted is out of date and the run must be posted again
 * (P8-3). SUPERSEDED: a later run for the same date replaced it.
 *
 * @param voucherId the closing-stock voucher, or null when nothing needed posting
 */
public record ValuationRun(String id, LocalDate date, BigDecimal value, String voucherId, Status status) {

    public enum Status { CURRENT, STALE, SUPERSEDED }
}
