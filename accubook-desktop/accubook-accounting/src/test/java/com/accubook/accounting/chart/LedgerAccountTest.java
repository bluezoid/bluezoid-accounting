package com.accubook.accounting.chart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity, governance and label are three separate things.
 *
 * <p>The reference application conflates the first with the third, resolving
 * control accounts by display name, which is why renaming one there creates a
 * duplicate.
 */
class LedgerAccountTest {

    @Test
    @DisplayName("a system account has a stable handle")
    void systemAccountHasHandle() {
        LedgerAccount account = new LedgerAccount("id", "g", "ROUND_OFF", "Round Off",
                AccountNature.EXPENSES, true, true);

        assertTrue(account.hasStableHandle());
    }

    @Test
    @DisplayName("a user account has no stable handle")
    void userAccountHasNoHandle() {
        LedgerAccount account = new LedgerAccount("id", "g", null, "Stationery",
                AccountNature.EXPENSES, false, true);

        assertFalse(account.hasStableHandle());
    }

    @Test
    @DisplayName("the handle is independent of the display name")
    void handleIsIndependentOfName() {
        // P6-2: the customer may call this "Rounding A/c"; the module still
        // resolves ROUND_OFF.
        LedgerAccount renamed = new LedgerAccount("id", "g", "ROUND_OFF", "Rounding A/c",
                AccountNature.EXPENSES, true, true);

        assertTrue(renamed.hasStableHandle());
    }
}
