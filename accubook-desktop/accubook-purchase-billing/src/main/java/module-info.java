/**
 * The purchase bills and debit notes - a thin feature module over the shared
 * document engine in {@code accubook.trade}.
 *
 * <p>Requires neither persistence, licensing, inventory nor the other document
 * module. Stock is reached through {@code StockPort}; a build without
 * inventory has free-text lines only.
 */
module accubook.purchase.billing {
    requires accubook.trade;

    exports com.accubook.purchase;

    provides com.accubook.module.FeatureModule with com.accubook.purchase.PurchaseBillingModule;
}
