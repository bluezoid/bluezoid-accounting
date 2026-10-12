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
 *   Sales invoice   Dr customer total   Cr Sales taxable    Cr Output GST      round off: Cr if up, Dr if down
 *   Credit note     Cr customer total   Dr Sales taxable    Dr Output GST      round off: Dr if up, Cr if down
 *   Purchase bill   Cr vendor total     Dr Purchases        Dr Input GST       round off: Dr if up, Cr if down
 *   Debit note      Dr vendor total     Cr Purchases        Cr Input GST       round off: Cr if up, Dr if down
 * </pre>
 *
 * Under reverse charge the vendor is owed the taxable value only, and the tax
 * is owed to the government instead: an RCM Payable leg on the party's side.
 * Non-creditable GST (composition, unregistered) is part of the Purchases
 * amount, not a leg of its own.
 *
 * <p>Periodic 5a: no cost of goods sold, no stock ledger.
 */
public final class PostingPlan {

    private PostingPlan() {
    }

    /**
     * A tax posting.
     *
     * @param withCounter true for output or input GST, posted on the side of
     *                    Sales or Purchases; false for reverse-charge tax payable,
     *                    posted on the party's side
     */
    public record TaxLeg(String ledgerId, BigDecimal amount, boolean withCounter) {
    }

    /** A document with no tax: the counter ledger takes the whole amount. */
    public static List<EntryLine> entries(DocumentKind kind, String partyLedgerId, String counterLedgerId,
                                          String roundOffLedgerId, RoundOff money, String narration) {
        return entries(kind, partyLedgerId, counterLedgerId, money.amount(), roundOffLedgerId, money, List.of(),
                narration);
    }

    /**
     * @param counterAmount what Sales or Purchases takes: the taxable value, plus
     *                      any GST that cannot be credited
     */
    public static List<EntryLine> entries(DocumentKind kind, String partyLedgerId, String counterLedgerId,
                                          BigDecimal counterAmount, String roundOffLedgerId, RoundOff money,
                                          List<TaxLeg> taxes, String narration) {
        if (money.total().signum() <= 0) {
            throw new TradeException("A document must have a total above zero to post");
        }
        List<EntryLine> entries = new ArrayList<>(3 + taxes.size());
        boolean partyDebit = kind.partyDebited();
        entries.add(line(partyLedgerId, money.total(), partyDebit, narration));
        if (counterAmount.signum() > 0) {
            entries.add(line(counterLedgerId, counterAmount, !partyDebit, narration));
        }
        for (TaxLeg tax : taxes) {
            if (tax.amount().signum() > 0) {
                entries.add(line(tax.ledgerId(), tax.amount(), tax.withCounter() != partyDebit, narration));
            }
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
