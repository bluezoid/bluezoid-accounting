/**
 * The sales invoices and credit notes - a thin feature module over the shared
 * document engine in {@code accubook.trade}.
 *
 * <p>Requires neither persistence, licensing, inventory nor the other document
 * module. Stock is reached through {@code StockPort}; a build without
 * inventory has free-text lines only.
 */
module accubook.sales.invoicing {
    requires accubook.trade;

    exports com.accubook.sales;

    provides com.accubook.module.FeatureModule with com.accubook.sales.SalesInvoicingModule;
}
