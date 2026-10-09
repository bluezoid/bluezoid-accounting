package com.accubook.trade;

import com.accubook.accounting.posting.EntryLine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The voucher a document posts. A pure function of kind and amounts, so the
 * D-23 property - a note moves every ledger opposite to its parent - is
 * tested without a database.
 *
 * <pre>
 *   Sales invoice   Dr customer total   Cr Sales amount      round off: Cr if up, Dr if down
 *   Credit note     Cr customer total   Dr Sales amount      round off: Dr if up, Cr if down
 *   Purchase bill   Cr vendor total     Dr Purchases amount  round off: Dr if up, Cr if down
 *   Debit note      Dr vendor total     Cr Purchases amount  round off: Cr if up, Dr if down
 * </pre>
 *
 * Periodic 5a: no cost of goods sold, no stock ledger. Tax lines arrive in P9.
 */
public final class PostingPlan {

    private PostingPlan() {
    }

    public static List<EntryLine> entries(DocumentKind kind, String partyLedgerId, String counterLedgerId,
                                          String roundOffLedgerId, RoundOff money, String narration) {
        if (money.total().signum() <= 0) {
            throw new TradeException("A document must have a total above zero to post");
        }
        List<EntryLine> entries = new ArrayList<>(3);
        boolean partyDebit = kind.partyDebited();
        entries.add(line(partyLedgerId, money.total(), partyDebit, narration));
        if (money.amount().signum() > 0) {
            entries.add(line(counterLedgerId, money.amount(), !partyDebit, narration));
        }
        int direction = money.roundOff().signum();
        if (direction != 0) {
            // Rounding up adds to what the party owes or is owed, so Round Off
            // takes the counter's side; rounding down, the party's.
            boolean roundOffDebit = direction > 0 ? !partyDebit : partyDebit;
            entries.add(line(roundOffLedgerId, money.roundOff().abs(), roundOffDebit, "Round off"));
        }
        return entries;
    }

    private static EntryLine line(String ledgerId, BigDecimal amount, boolean debit, String narration) {
        return debit
                ? new EntryLine(ledgerId, amount, BigDecimal.ZERO, narration)
                : new EntryLine(ledgerId, BigDecimal.ZERO, amount, narration);
    }
}
