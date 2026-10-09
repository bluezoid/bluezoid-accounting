/**
 * The engine shared by sales and purchase documents.
 *
 * <p>A library, not a feature module: it owns no tables, is never entitled and
 * is never discovered. {@code accubook.sales.invoicing} and
 * {@code accubook.purchase.billing} each depend on it, which P4 permits - they
 * share code without either feature depending on the other.
 *
 * <p>It never requires {@code accubook.inventory}: stock is reached through
 * {@code StockPort}, which is absent in a build without inventory.
 */
module accubook.trade {
    requires transitive accubook.platform;
    requires transitive accubook.module.api;
    requires transitive accubook.accounting;

    uses com.accubook.module.stock.StockPort;

    exports com.accubook.trade;
}
