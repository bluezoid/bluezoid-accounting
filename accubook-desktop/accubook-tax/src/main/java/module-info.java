/**
 * Statutory tax rules - kernel, in every build (B-7: GST and TDS computation are
 * not optional; only return generation is an add-on).
 *
 * <p>Rates, thresholds, slabs and state codes are <strong>data</strong>, in
 * signed, effective-dated rule packs (P9-4), so a Finance Act change can reach a
 * customer without a new application release, and a historical period keeps the
 * rules that applied then.
 */
module accubook.tax {
    requires accubook.platform;
    requires accubook.persistence;
    requires java.sql;

    exports com.accubook.tax;
}
