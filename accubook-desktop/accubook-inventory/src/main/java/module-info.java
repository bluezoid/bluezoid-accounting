/**
 * Inventory: items, units, warehouses and stock quantities.
 *
 * <p>The first optional feature module. Note what it does not require:
 * {@code accubook.persistence} (it stores through {@code ModuleTransactions},
 * which is always guarded) and {@code accubook.licensing} (it sees only a
 * {@code ModuleState}). Both are asserted by a test.
 *
 * <p>Physically absent from a BASIC build: its code, use cases and screens are
 * not in the image. Its tables exist in every build regardless (universal
 * schema), so a downgraded customer keeps their history.
 */
module accubook.inventory {
    requires accubook.platform;
    requires accubook.module.api;

    exports com.accubook.inventory;

    provides com.accubook.module.FeatureModule with com.accubook.inventory.InventoryModule;
}
