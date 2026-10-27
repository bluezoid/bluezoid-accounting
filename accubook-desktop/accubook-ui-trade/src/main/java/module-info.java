/**
 * Sales and purchase screens (Phase 11S), written once and given a side. Two
 * providers, keyed to sales-invoicing and purchase-billing, so each side's
 * screens show or hide with its own module's state.
 */
module accubook.ui.trade {
    requires accubook.ui.api;
    requires accubook.trade;
    requires accubook.accounting;

    provides com.accubook.ui.api.ScreenProvider with com.accubook.ui.trade.SalesScreens,
            com.accubook.ui.trade.PurchaseScreens;
}
