/**
 * The sealed accounting posting core.
 *
 * <p>{@code posting} is exported so use cases can post. {@code posting.internal}
 * is <strong>not</strong> exported: it holds the only code that writes
 * {@code voucher} and {@code voucher_entry}, and nothing outside this module may
 * reference it.
 *
 * <p>That is the structural answer to the reference application's failure. It
 * had a careful posting helper - exact balance comparison, tenant scoping,
 * unresolved-ledger detection - and ten call sites, six of which wrote
 * {@code voucher} themselves. A helper is only a choke point for code that
 * chooses to call it.
 */
module accubook.accounting {
    requires accubook.platform;
    requires accubook.persistence;
    requires accubook.module.api;
    requires java.sql;

    exports com.accubook.accounting.posting;
    exports com.accubook.accounting.chart;
}
