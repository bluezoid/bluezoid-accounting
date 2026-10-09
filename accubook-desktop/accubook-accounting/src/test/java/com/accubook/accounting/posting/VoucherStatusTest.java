package com.accubook.accounting.posting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifecycle exists to make contradictory states unrepresentable.
 *
 * <p>Finding A-2: with independent {@code status} and {@code is_posted} columns,
 * CANCELLED + posted was expressible - a voucher counted in the trial balance
 * that every screen showed as cancelled.
 */
class VoucherStatusTest {

    @Test
    @DisplayName("a posted voucher can only become reversed")
    void postedOnlyReverses() {
        assertAll(
                () -> assertTrue(VoucherStatus.POSTED.canTransitionTo(VoucherStatus.REVERSED)),
                () -> assertFalse(VoucherStatus.POSTED.canTransitionTo(VoucherStatus.CANCELLED),
                        "A-2: cancelling a posted voucher would silently restate the ledger"),
                () -> assertFalse(VoucherStatus.POSTED.canTransitionTo(VoucherStatus.DRAFT),
                        "posting is one-way"));
    }

    @Test
    @DisplayName("a draft may be posted or cancelled")
    void draftTransitions() {
        assertAll(
                () -> assertTrue(VoucherStatus.DRAFT.canTransitionTo(VoucherStatus.POSTED)),
                () -> assertTrue(VoucherStatus.DRAFT.canTransitionTo(VoucherStatus.CANCELLED)),
                () -> assertFalse(VoucherStatus.DRAFT.canTransitionTo(VoucherStatus.REVERSED),
                        "nothing to reverse - it never entered the ledger"));
    }

    @ParameterizedTest
    @EnumSource(value = VoucherStatus.class, names = {"REVERSED", "CANCELLED"})
    @DisplayName("terminal states permit no transition at all")
    void terminalStatesAreFinal(VoucherStatus terminal) {
        assertTrue(terminal.isTerminal());
        for (VoucherStatus target : VoucherStatus.values()) {
            assertFalse(terminal.canTransitionTo(target),
                    terminal + " must not transition to " + target);
        }
    }

    @Test
    @DisplayName("a reversed voucher still counts as posted - it stays in the trial balance")
    void reversedRemainsPosted() {
        // It and its contra sum to zero. Removing it from the ledger would make
        // the reversal look like a deletion, which is not what happened.
        assertAll(
                () -> assertTrue(VoucherStatus.REVERSED.isPosted()),
                () -> assertTrue(VoucherStatus.POSTED.isPosted()),
                () -> assertFalse(VoucherStatus.DRAFT.isPosted()),
                () -> assertFalse(VoucherStatus.CANCELLED.isPosted()));
    }
}
