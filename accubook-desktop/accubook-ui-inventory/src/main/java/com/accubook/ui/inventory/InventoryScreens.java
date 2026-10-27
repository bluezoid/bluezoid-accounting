package com.accubook.ui.inventory;

import com.accubook.inventory.InventoryModule;
import com.accubook.module.ModuleId;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.Section;

import java.util.List;

/** Inventory's screens, found by the shell only when inventory is in the build. */
public final class InventoryScreens implements ScreenProvider {

    public static final String ITEMS = "items";
    public static final String STOCK_SETUP = "stock-setup";

    @Override
    public ModuleId module() {
        return InventoryModule.ID;
    }

    @Override
    public List<NavigationEntry> entries() {
        return List.of(
                NavigationEntry.of(ITEMS, Section.MASTERS, "Items", 30, ItemsScreen::new),
                NavigationEntry.of(STOCK_SETUP, Section.MASTERS, "Warehouses and opening stock", 40,
                        StockSetupScreen::new));
    }
}
