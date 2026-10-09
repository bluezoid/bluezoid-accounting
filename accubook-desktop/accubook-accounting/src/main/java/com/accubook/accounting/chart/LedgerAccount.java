package com.accubook.accounting.chart;

/**
 * A ledger account, as the governance layer sees it.
 *
 * <p>Three identity concepts are kept deliberately separate, and conflating any
 * two of them is how the reference application went wrong:
 *
 * <ul>
 *   <li>{@code id} - the primary key</li>
 *   <li>{@code systemCode} - the stable handle a module resolves on, never
 *       displayed</li>
 *   <li>{@code name} - the human label, freely renameable</li>
 * </ul>
 *
 * <p>The reference resolves control accounts by {@code name} through
 * {@code getOrCreateNamedLedger}, so renaming "Round Off" silently creates a
 * second one and splits the balance across both. Here a rename touches nothing
 * a module depends on.
 *
 * <p>{@code system} is separate again: it governs whether a customer may delete
 * or deactivate the account, which is not the same question as whether code
 * resolves it.
 */
public record LedgerAccount(
        String id,
        String groupId,
        String systemCode,
        String name,
        AccountNature nature,
        boolean system,
        boolean active) {

    /** True when a module resolves this account programmatically. */
    public boolean hasStableHandle() {
        return systemCode != null && !systemCode.isBlank();
    }
}
