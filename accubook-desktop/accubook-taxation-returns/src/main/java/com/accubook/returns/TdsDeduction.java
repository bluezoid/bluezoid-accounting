package com.accubook.returns;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A deduction of TDS (or collection of TCS) on one document, net of the notes
 * that reversed part of it.
 *
 * @param section     as resolved, e.g. 194C-OTH
 * @param amount      deducted, less reversals by notes
 * @param challanId   the deposit that paid it, or null while undeposited
 */
public record TdsDeduction(String entryId, String kind, String section, String partyId, String partyName,
                          String pan, String documentNumber, LocalDate date, BigDecimal base, BigDecimal rate,
                          BigDecimal amount, String challanId, LocalDate depositDate) {

    public LocalDate dueDate() {
        return TdsInterest.dueDate(date);
    }

    /** s.201(1A) interest if deposited on {@code on} (P9-23). */
    public BigDecimal interestIfDepositedOn(LocalDate on) {
        return TdsInterest.interest(amount, date, on);
    }

    /** The section a challan quotes: 194C for both 194C-IND and 194C-OTH. */
    public String challanSection() {
        int dash = section.indexOf('-');
        return dash < 0 ? section : section.substring(0, dash);
    }
}
