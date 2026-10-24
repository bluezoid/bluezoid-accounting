/**
 * Banking: bank details of Cash &amp; Bank ledgers, statement import and bank
 * reconciliation (Phase 10). Professional and Enterprise (B-7).
 *
 * <p>It owns no balance: a bank account's balance is its ledger's. It reads the
 * ledger's voucher entries to match statement lines, and uses the kernel
 * posting core only to post bank-only items from a statement line (P10-4).
 * Removing it never deletes those vouchers.
 */
module accubook.banking {
    requires accubook.platform;
    requires accubook.module.api;
    requires accubook.accounting;
    requires java.sql;

    exports com.accubook.banking;

    provides com.accubook.module.FeatureModule with com.accubook.banking.BankingModule;
}
