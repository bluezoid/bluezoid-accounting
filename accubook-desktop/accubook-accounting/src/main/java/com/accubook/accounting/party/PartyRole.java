package com.accubook.accounting.party;

/**
 * What a party is to the business.
 *
 * <p>Decides only where a <em>new</em> party's ledger is placed (P7-2): one
 * ledger per party, under Sundry Debtors for a customer or a party that is
 * both, under Sundry Creditors for a vendor. A party that is both buyer and
 * seller keeps one ledger, and its net balance is the true position - the
 * Tally convention. The reference instead treats {@code BOTH} as a customer
 * without saying so (R-8).
 */
public enum PartyRole {
    CUSTOMER("SUNDRY_DEBTORS"),
    VENDOR("SUNDRY_CREDITORS"),
    BOTH("SUNDRY_DEBTORS");

    private final String defaultGroupCode;

    PartyRole(String defaultGroupCode) {
        this.defaultGroupCode = defaultGroupCode;
    }

    /** {@code system_code} of the ledger group a new party's ledger goes in. */
    public String defaultGroupCode() {
        return defaultGroupCode;
    }

    /**
     * Whether a ledger in {@code groupCode} is a valid home for this role.
     *
     * <p>A customer belongs in Debtors and a vendor in Creditors. A party that
     * is both may sit in either: a vendor with history who starts buying from
     * us keeps its ledger where its history is.
     */
    public boolean acceptsGroup(String groupCode) {
        return this == BOTH
                ? "SUNDRY_DEBTORS".equals(groupCode) || "SUNDRY_CREDITORS".equals(groupCode)
                : defaultGroupCode.equals(groupCode);
    }

    public boolean sells() {
        return this != VENDOR;
    }

    public boolean buys() {
        return this != CUSTOMER;
    }
}
