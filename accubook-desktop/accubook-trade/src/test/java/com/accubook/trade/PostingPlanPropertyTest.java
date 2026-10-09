package com.accubook.trade;

import com.accubook.accounting.posting.EntryLine;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-23, as a property. The reference posts a credit note exactly like an
 * invoice, so a sales return increases revenue and receivables.
 */
class PostingPlanPropertyTest {

    private static Map<String, BigDecimal> net(List<EntryLine> entries) {
        Map<String, BigDecimal> net = new HashMap<>();
        for (EntryLine e : entries) {
            net.merge(e.ledgerId(), e.debit().subtract(e.credit()), BigDecimal::add);
        }
        return net;
    }

    private static RoundOff money(long paise, boolean round) {
        return RoundOff.of(BigDecimal.valueOf(paise, 2), round);
    }

    @Property(tries = 1000)
    void everyPlanBalances(@ForAll @LongRange(min = 51, max = 10_000_000_000L) long paise,
                           @ForAll boolean round, @ForAll @IntRange(min = 0, max = 3) int kindIndex) {
        DocumentKind kind = DocumentKind.values()[kindIndex];
        List<EntryLine> entries = PostingPlan.entries(kind, "party", "counter", "roundoff", money(paise, round), null);

        BigDecimal debit = entries.stream().map(EntryLine::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entries.stream().map(EntryLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(credit);
        // The party carries exactly the document total. (Total debits are not
        // the total: rounding 1.49 down to 1.00 posts Dr party 1.00 and
        // Dr Round Off 0.49 against Cr Sales 1.49.)
        EntryLine party = entries.stream().filter(e -> e.ledgerId().equals("party")).findFirst().orElseThrow();
        assertThat(party.debit().add(party.credit())).isEqualByComparingTo(money(paise, round).total());
    }

    @Property(tries = 1000)
    void aNoteMirrorsItsParentOnEveryLedger(@ForAll @LongRange(min = 51, max = 10_000_000_000L) long paise,
                                            @ForAll boolean round, @ForAll boolean sales) {
        DocumentKind parent = sales ? DocumentKind.SALES_INVOICE : DocumentKind.PURCHASE_BILL;
        RoundOff amounts = money(paise, round);

        Map<String, BigDecimal> forward = net(PostingPlan.entries(parent, "party", "counter", "roundoff", amounts, null));
        Map<String, BigDecimal> back = net(PostingPlan.entries(parent.note(), "party", "counter", "roundoff", amounts, null));

        assertThat(back.keySet()).isEqualTo(forward.keySet());
        forward.forEach((ledger, value) -> assertThat(back.get(ledger)).isEqualByComparingTo(value.negate()));
    }

    @Property(tries = 200)
    void anInvoiceRaisesReceivablesAndRevenue_aCreditNoteLowersThem(
            @ForAll @LongRange(min = 100, max = 10_000_000L) long paise) {
        RoundOff amounts = money(paise, false);
        Map<String, BigDecimal> invoice = net(PostingPlan.entries(DocumentKind.SALES_INVOICE, "customer", "sales", "ro", amounts, null));
        Map<String, BigDecimal> note = net(PostingPlan.entries(DocumentKind.CREDIT_NOTE, "customer", "sales", "ro", amounts, null));

        assertThat(invoice.get("customer").signum()).isPositive();   // receivable up (debit)
        assertThat(invoice.get("sales").signum()).isNegative();      // revenue up (credit)
        assertThat(note.get("customer").signum()).isNegative();      // receivable down
        assertThat(note.get("sales").signum()).isPositive();         // revenue down
    }
}
