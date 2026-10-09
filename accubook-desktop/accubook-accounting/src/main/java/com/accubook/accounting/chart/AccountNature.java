package com.accubook.accounting.chart;

/**
 * The five natures a ledger group can have, and which side increases a balance.
 *
 * <p>Nature belongs to the <em>group</em>, not the ledger: a ledger inherits it.
 * That is why moving a ledger between groups of different nature is refused once
 * it has postings - the accumulated history was built under one convention and
 * every future entry would apply the other.
 */
public enum AccountNature {

    ASSETS(true),
    EXPENSES(true),
    LIABILITIES(false),
    INCOME(false),
    EQUITY(false);

    private final boolean debitNatured;

    AccountNature(boolean debitNatured) {
        this.debitNatured = debitNatured;
    }

    /** True when a debit increases this account's balance. */
    public boolean isDebitNatured() {
        return debitNatured;
    }

    /** Which side a positive balance sits on, for presentation. */
    public String normalSide() {
        return debitNatured ? "DR" : "CR";
    }
}
