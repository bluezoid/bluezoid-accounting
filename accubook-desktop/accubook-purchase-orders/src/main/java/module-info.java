/**
 * Purchase orders - a thin feature module over the order engine in
 * {@code accubook.trade}. Requires inventory (declared in
 * {@code dependsOn}); reaches it only through {@code StockPort}.
 */
module accubook.purchase.orders {
    requires accubook.trade;

    exports com.accubook.purchaseorders;

    provides com.accubook.module.FeatureModule with com.accubook.purchaseorders.PurchaseOrdersModule;
}
