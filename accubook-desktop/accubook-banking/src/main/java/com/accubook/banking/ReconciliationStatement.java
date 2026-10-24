package com.accubook.banking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The bank reconciliation statement as of a date: from the balance per books
 * to the balance per bank. {@link #difference()} is nil when every item is
 * explained.
 *
 * @param issuedNotPresented   book payments the bank has not yet paid (added back)
 * @param depositedNotCredited book receipts the bank has not yet credited (deducted)
 * @param bankOnly             statement lines not in the books - charges, interest,
 *                             direct debits - with their sign
 * @param openingDifference    the statement's balance less the books' at the start of
 *                             reconciliation (P10-5): items outstanding before it
 * @param balancePerBank       the statement's own balance on that date, if known
 */
public record ReconciliationStatement(String ledgerId, LocalDate asOf, BigDecimal balancePerBooks,
                                      BigDecimal openingDifference, List<BookEntry> issuedNotPresented,
                                      List<BookEntry> depositedNotCredited, List<StoredLine> bankOnly,
                                      BigDecimal computedBankBalance, BigDecimal balancePerBank) {

    public ReconciliationStatement {
        issuedNotPresented = List.copyOf(issuedNotPresented);
        depositedNotCredited = List.copyOf(depositedNotCredited);
        bankOnly = List.copyOf(bankOnly);
    }

    /** Computed less stated bank balance; nil once everything is reconciled. */
    public BigDecimal difference() {
        return balancePerBank == null ? null : computedBankBalance.subtract(balancePerBank);
    }
}
