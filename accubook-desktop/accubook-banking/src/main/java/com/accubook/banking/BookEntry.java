package com.accubook.banking;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A posted voucher entry on a bank ledger: what the books say moved through the
 * account.
 *
 * @param net        into the account positive (a debit to the bank ledger), out negative
 * @param references what could appear on the statement: the voucher's reference and the
 *                   receipt's or payment's cheque or UTR number
 * @param parties    the names of the other ledgers in the voucher
 */
public record BookEntry(String entryId, String voucherNumber, LocalDate date, BigDecimal net, String narration,
                        String references, String parties) {
}
