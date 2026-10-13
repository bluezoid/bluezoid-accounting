package com.accubook.returns;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A deposit (ITNS 281) of deductions of one section.
 *
 * @param bsrCode         the bank branch's 7-digit BSR code
 * @param challanSerial   the 5-digit challan serial number
 * @param interest        interest paid with it; s.201(1A) interest computed short of this is flagged
 * @param accountLedgerId a Cash &amp; Bank ledger it was paid from
 */
public record ChallanRequest(LocalDate depositDate, String bsrCode, String challanSerial, List<String> entryIds,
                             BigDecimal interest, BigDecimal fee, String accountLedgerId) {

    public ChallanRequest {
        Objects.requireNonNull(depositDate, "depositDate");
        Objects.requireNonNull(accountLedgerId, "accountLedgerId");
        entryIds = List.copyOf(entryIds);
        interest = interest == null ? BigDecimal.ZERO : interest;
        fee = fee == null ? BigDecimal.ZERO : fee;
        if (bsrCode == null || !bsrCode.matches("\\d{7}")) {
            throw new ReturnException("A BSR code is 7 digits");
        }
        if (challanSerial == null || !challanSerial.matches("\\d{5}")) {
            throw new ReturnException("A challan serial number is 5 digits");
        }
        if (entryIds.isEmpty()) {
            throw new ReturnException("A deposit pays at least one deduction");
        }
        if (interest.signum() < 0 || fee.signum() < 0) {
            throw new ReturnException("Interest and fee cannot be negative");
        }
    }
}
