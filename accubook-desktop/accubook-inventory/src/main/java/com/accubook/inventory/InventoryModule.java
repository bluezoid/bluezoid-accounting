package com.accubook.inventory;

import com.accubook.module.FeatureModule;
import com.accubook.module.HistoricalView;
import com.accubook.module.HistoricalView.Column;
import com.accubook.module.HistoricalView.Column.Kind;
import com.accubook.module.ModuleId;

import java.util.List;
import java.util.Set;

/**
 * The inventory module's identity, ownership and historical views.
 *
 * <p>Trivial to construct, as {@code ServiceLoader} requires: discovery runs in
 * every build, entitled or not, so nothing here may touch a database.
 */
public final class InventoryModule implements FeatureModule {

    public static final ModuleId ID = ModuleId.of("inventory");

    /** Every table this module owns. The persistence guard reads this. */
    static final Set<String> TABLES = Set.of(
            "unit", "item_category", "item", "warehouse", "stock", "stock_movement");

    @Override
    public ModuleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Inventory";
    }

    @Override
    public int navigationOrder() {
        return 30;
    }

    @Override
    public Set<String> ownedTables() {
        return TABLES;
    }

    /** Migration 5 created the inventory tables. */
    @Override
    public int requiredSchemaVersion() {
        return 5;
    }

    /**
     * What a customer without this module can still see of their inventory
     * history, in the generic records viewer (B-8).
     */
    @Override
    public List<HistoricalView> historicalViews() {
        return List.of(
                new HistoricalView("Items", "item", List.of(
                        new Column("name", "Item", Kind.TEXT),
                        new Column("sku", "SKU", Kind.TEXT),
                        new Column("kind", "Kind", Kind.TEXT),
                        new Column("hsn_sac", "HSN/SAC", Kind.TEXT)), "name", false),
                new HistoricalView("Stock movements", "stock_movement", List.of(
                        new Column("movement_date", "Date", Kind.DATE),
                        new Column("movement_type", "Type", Kind.TEXT),
                        new Column("item_id", "Item", Kind.TEXT),
                        new Column("quantity_e4", "Quantity", Kind.QUANTITY),
                        new Column("source_type", "Source", Kind.TEXT),
                        new Column("narration", "Narration", Kind.TEXT)), "movement_date", true));
    }
}
