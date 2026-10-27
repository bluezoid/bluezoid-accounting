/**
 * Inventory's screens (Phase 11S): items with their units and GST, warehouses
 * and opening stock. Requires its feature and the screen contract only; the
 * shell finds it through {@code ScreenProvider} and never requires it.
 */
module accubook.ui.inventory {
    requires accubook.ui.api;
    requires accubook.inventory;
    requires accubook.accounting;

    provides com.accubook.ui.api.ScreenProvider with com.accubook.ui.inventory.InventoryScreens;
}
