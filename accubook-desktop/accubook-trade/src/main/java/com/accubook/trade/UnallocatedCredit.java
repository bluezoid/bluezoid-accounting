package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Money received or credited that is not yet applied to any document: an advance. */
public record UnallocatedCredit(Allocation.SourceType sourceType, String sourceId, String number, String partyId,
                                LocalDate date, BigDecimal unallocated) {
}
