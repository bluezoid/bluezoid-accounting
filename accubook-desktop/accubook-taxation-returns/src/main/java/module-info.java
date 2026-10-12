/**
 * GST and TDS returns - the {@code taxation-returns} add-on, purchasable at any
 * tier (B-7).
 *
 * <p>It only reads what every plan records - documents, their tax lines and
 * withholding entries - and owns only its filed returns. Computation stays in
 * the kernel: a build without this module records tax identically and cannot
 * generate returns. It requires neither persistence, licensing nor any document
 * module: it reads their tables through {@code ModuleTransactions}.
 */
module accubook.taxation.returns {
    requires accubook.platform;
    requires accubook.module.api;
    requires accubook.tax;
    requires java.sql;

    exports com.accubook.returns;

    provides com.accubook.module.FeatureModule with com.accubook.returns.TaxationReturnsModule;
}
