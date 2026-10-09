package com.accubook.accounting.posting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A journal line is one-sided and positive, or it is not a journal line. */
class EntryLineTest {

    private static final String LEDGER = "ledger-1";
    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @Test
    @DisplayName("a debit line has a debit and no credit")
    void debitLine() {
        EntryLine line = EntryLine.debit(LEDGER, HUNDRED);

        assertAll(
                () -> assertTrue(line.isDebit()),
                () -> assertEquals(HUNDRED, line.debit()),
                () -> assertEquals(0, line.credit().signum()));
    }

    @Test
    @DisplayName("an entry cannot be both debit and credit")
    void refusesBothSides() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EntryLine(LEDGER, HUNDRED, HUNDRED, null));

        assertTrue(e.getMessage().contains("cannot be both"), e.getMessage());
    }

    @Test
    @DisplayName("an entry cannot be zero on both sides")
    void refusesZeroEntry() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new EntryLine(LEDGER, BigDecimal.ZERO, BigDecimal.ZERO, null));

        assertTrue(e.getMessage().contains("must have a non-zero"), e.getMessage());
    }

    @Test
    @DisplayName("negative amounts are refused - the other side expresses direction")
    void refusesNegative() {
        // A negative debit is a credit. Allowing both spellings is how sign
        // confusion enters a ledger.
        assertThrows(IllegalArgumentException.class,
                () -> new EntryLine(LEDGER, new BigDecimal("-100.00"), BigDecimal.ZERO, null));
        assertThrows(IllegalArgumentException.class,
                () -> new EntryLine(LEDGER, BigDecimal.ZERO, new BigDecimal("-100.00"), null));
    }

    @Test
    @DisplayName("reversing exchanges the sides and preserves the amount")
    void reversalSwapsSides() {
        EntryLine reversed = EntryLine.debit(LEDGER, HUNDRED).reversed();

        assertAll(
                () -> assertTrue(!reversed.isDebit()),
                () -> assertEquals(HUNDRED, reversed.credit()),
                () -> assertEquals(LEDGER, reversed.ledgerId()));
    }

    @Test
    @DisplayName("a blank ledger id is refused")
    void refusesBlankLedger() {
        assertThrows(IllegalArgumentException.class,
                () -> EntryLine.debit("  ", HUNDRED));
    }
}
