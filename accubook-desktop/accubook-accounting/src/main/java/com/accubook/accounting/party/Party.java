package com.accubook.accounting.party;

/**
 * A customer or vendor, and the ledger that carries its balance.
 *
 * <p>{@code name} and {@code active} are read from the ledger, which is their
 * source of truth: the chart of accounts may rename or deactivate any ledger,
 * and the party follows.
 */
public record Party(
        String id,
        String ledgerId,
        PartyRole role,
        String name,
        PartyProfile profile,
        boolean active) {
}
