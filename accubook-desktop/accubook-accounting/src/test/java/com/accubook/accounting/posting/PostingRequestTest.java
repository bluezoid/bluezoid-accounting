package com.accubook.accounting.posting;

import com.accubook.module.ModuleId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validity is asserted at construction, so an invalid request cannot exist to be
 * posted later by code that assumed someone else had checked.
 */
class PostingRequestTest {

    private static final ModuleId ORIGINATOR = ModuleId.of("sales-invoicing");
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private static PostingRequest requestWith(List<EntryLine> entries) {
        return new PostingRequest(ORIGINATOR, "fy-1", "vt-1",
                LocalDate.of(2026, 4, 1), null, "test", entries, null);
    }

    @Test
    @DisplayName("a balanced two-line voucher is accepted")
    void acceptsBalanced() {
        PostingRequest request = requestWith(List.of(
                EntryLine.debit("cash", HUNDRED),
                EntryLine.credit("sales", HUNDRED)));

        assertAll(
                () -> assertEquals(0, request.totalDebit().compareTo(HUNDRED)),
                () -> assertEquals(0, request.totalCredit().compareTo(HUNDRED)));
    }

    @Test
    @DisplayName("A-1: an empty voucher is refused even though 0 = 0 balances")
    void refusesEmptyVoucher() {
        // The whole point of finding A-1. Zero totals satisfy
        // CHECK (total_debit_e4 = total_credit_e4) and every balance check that
        // only compares the two sides.
        PostingException e = assertThrows(PostingException.class,
                () -> requestWith(List.of()));

        assertTrue(e.getMessage().contains("at least two entries"), e.getMessage());
    }

    @Test
    @DisplayName("A-1: a single-entry voucher is refused")
    void refusesSingleEntry() {
        assertThrows(PostingException.class,
                () -> requestWith(List.of(EntryLine.debit("cash", HUNDRED))));
    }

    @Test
    @DisplayName("an unbalanced voucher is refused")
    void refusesUnbalanced() {
        UnbalancedPostingException e = assertThrows(UnbalancedPostingException.class,
                () -> requestWith(List.of(
                        EntryLine.debit("cash", HUNDRED),
                        EntryLine.credit("sales", new BigDecimal("90.00")))));

        assertAll(
                () -> assertEquals(0, e.totalDebit().compareTo(HUNDRED)),
                () -> assertTrue(e.getMessage().contains("does not balance")));
    }

    @Test
    @DisplayName("F-3 regression: a half-paisa difference is refused - there is no epsilon")
    void refusesSubPaisaDifference() {
        // The reference gates its journal path with closeEnough(a, b, "0.01"),
        // so this voucher would post there. Scaled-integer storage means there
        // is no rounding here for an epsilon to absorb.
        assertThrows(UnbalancedPostingException.class,
                () -> requestWith(List.of(
                        EntryLine.debit("cash", new BigDecimal("100.0050")),
                        EntryLine.credit("sales", HUNDRED))));
    }

    @Test
    @DisplayName("differing scale is not a difference in value")
    void toleratesScaleDifference() {
        // 100.0 and 100.00 are the same amount. Using equals() rather than
        // compareTo() here would reject a perfectly balanced voucher.
        PostingRequest request = requestWith(List.of(
                EntryLine.debit("cash", new BigDecimal("100.0")),
                EntryLine.credit("sales", new BigDecimal("100.00"))));

        assertEquals(0, request.totalDebit().compareTo(request.totalCredit()));
    }

    @Test
    @DisplayName("a multi-line voucher balances across all lines, not pairwise")
    void acceptsMultiLine() {
        PostingRequest request = requestWith(List.of(
                EntryLine.debit("expense", new BigDecimal("90.00")),
                EntryLine.debit("gst-input", new BigDecimal("10.00")),
                EntryLine.credit("vendor", HUNDRED)));

        assertEquals(0, request.totalDebit().compareTo(HUNDRED));
    }

    @Test
    @DisplayName("entries are defensively copied - a caller cannot mutate a validated request")
    void copiesEntries() {
        java.util.List<EntryLine> mutable = new java.util.ArrayList<>(List.of(
                EntryLine.debit("cash", HUNDRED),
                EntryLine.credit("sales", HUNDRED)));
        PostingRequest request = requestWith(mutable);

        mutable.add(EntryLine.debit("sneaky", HUNDRED));

        assertEquals(2, request.entries().size(),
                "a validated request must not change after validation");
    }

    @Test
    @DisplayName("idempotency key presence is reported")
    void reportsIdempotencyKey() {
        PostingRequest without = requestWith(List.of(
                EntryLine.debit("cash", HUNDRED), EntryLine.credit("sales", HUNDRED)));
        PostingRequest with = new PostingRequest(ORIGINATOR, "fy-1", "vt-1",
                LocalDate.of(2026, 4, 1), null, "test",
                List.of(EntryLine.debit("cash", HUNDRED), EntryLine.credit("sales", HUNDRED)),
                "key-1");

        assertAll(
                () -> assertTrue(!without.hasIdempotencyKey()),
                () -> assertTrue(with.hasIdempotencyKey()));
    }
}
