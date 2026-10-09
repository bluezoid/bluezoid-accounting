package com.accubook.inventory;

import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleTransactions;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Inventory use cases: masters and stock movements. Quantities only (B-1).
 *
 * <h2>Enforcement</h2>
 * Every mutating method asks {@link EntitlementGuard#requireWritable} first
 * (layer 1), and every write goes through {@link ModuleTransactions}, whose
 * connection is guarded (layer 2). A READ_ONLY inventory module is refused at
 * both, and its records stay readable - reads are never gated.
 *
 * <h2>Stock</h2>
 * The movement log is the record. {@code stock} is a cache of it, kept so that
 * an out-movement has one row to decrement under a guard:
 * {@code UPDATE ... WHERE quantity_e4 >= ?} decides in a single statement,
 * so two concurrent sales of the last unit cannot both succeed (P7-4). The
 * schema's {@code CHECK (quantity_e4 >= 0)} backs it up.
 */
public final class Inventory {

    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final Clock clock;

    public Inventory(ModuleTransactions transactions, EntitlementGuard entitlement, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Inventory(ModuleTransactions transactions, EntitlementGuard entitlement) {
        this(transactions, entitlement, Clock.systemUTC());
    }

    // ================================================================= units

    /**
     * @param decimals decimal places a quantity in this unit may carry, 0 to 4
     */
    public Unit createUnit(String symbol, String name, int decimals) {
        require("create a unit");
        String cleanSymbol = requireText(symbol, "A unit needs a symbol");
        String cleanName = requireText(name, "A unit needs a name");
        if (decimals < 0 || decimals > Scale.QUANTITY.digits()) {
            throw new InventoryException("A unit allows 0 to " + Scale.QUANTITY.digits()
                    + " decimal places");
        }
        Unit unit = new Unit(Ids.newIdString(), cleanSymbol, cleanName, decimals);
        write("create the unit", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO unit (id, symbol, name, decimals, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, unit.id());
                ps.setString(2, unit.symbol());
                ps.setString(3, unit.name());
                ps.setInt(4, unit.decimals());
                ps.setLong(5, now());
                ps.setLong(6, now());
                ps.executeUpdate();
            }
        });
        return unit;
    }

    public List<Unit> units() {
        return read(connection -> list(connection,
                "SELECT id, symbol, name, decimals FROM unit ORDER BY symbol",
                rows -> new Unit(rows.getString(1), rows.getString(2), rows.getString(3), rows.getInt(4))));
    }

    // ============================================================ categories

    public ItemCategory createCategory(String name, String parentId) {
        require("create a category");
        ItemCategory category = new ItemCategory(Ids.newIdString(), parentId,
                requireText(name, "A category needs a name"));
        write("create the category", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO item_category (id, parent_id, name, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, category.id());
                ps.setString(2, category.parentId());
                ps.setString(3, category.name());
                ps.setLong(4, now());
                ps.setLong(5, now());
                ps.executeUpdate();
            }
        });
        return category;
    }

    public List<ItemCategory> categories() {
        return read(connection -> list(connection,
                "SELECT id, parent_id, name FROM item_category ORDER BY name",
                rows -> new ItemCategory(rows.getString(1), rows.getString(2), rows.getString(3))));
    }

    // ================================================================= items

    public Item createItem(String name, ItemKind kind, String unitId, String sku, String categoryId,
                           String hsnSac, String description) {
        require("create an item");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(unitId, "unitId");
        Item item = new Item(Ids.newIdString(), requireText(name, "An item needs a name"),
                blankToNull(sku), kind, unitId, categoryId, blankToNull(hsnSac),
                blankToNull(description), true);
        write("create the item", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO item (id, category_id, unit_id, name, sku, kind, hsn_sac, "
                            + "description, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, item.id());
                ps.setString(2, item.categoryId());
                ps.setString(3, item.unitId());
                ps.setString(4, item.name());
                ps.setString(5, item.sku());
                ps.setString(6, item.kind().name());
                ps.setString(7, item.hsnSac());
                ps.setString(8, item.description());
                ps.setLong(9, now());
                ps.setLong(10, now());
                ps.executeUpdate();
            }
        });
        return item;
    }

    /** Rename an item or change its descriptive details. Kind and unit are separate. */
    public void updateItemDetails(String itemId, String name, String sku, String categoryId,
                                  String hsnSac, String description) {
        require("change an item");
        String cleanName = requireText(name, "An item needs a name");
        write("change the item", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE item SET name = ?, sku = ?, category_id = ?, hsn_sac = ?, description = ?, "
                            + "updated_at = ? WHERE id = ?")) {
                ps.setString(1, cleanName);
                ps.setString(2, blankToNull(sku));
                ps.setString(3, categoryId);
                ps.setString(4, blankToNull(hsnSac));
                ps.setString(5, blankToNull(description));
                ps.setLong(6, now());
                ps.setString(7, itemId);
                requireOneRow(ps.executeUpdate(), "No such item: " + itemId);
            }
        });
    }

    /**
     * Change an item's unit. Refused once the item has moved - every recorded
     * quantity would be reinterpreted (the schema refuses it too).
     */
    public void changeItemUnit(String itemId, String unitId) {
        require("change an item's unit");
        write("change the item's unit", connection -> {
            if (hasMovements(connection, itemId)) {
                throw new InventoryException("This item already has stock movements, so its unit "
                        + "cannot change: every recorded quantity would be reinterpreted.");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE item SET unit_id = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, unitId);
                ps.setLong(2, now());
                ps.setString(3, itemId);
                requireOneRow(ps.executeUpdate(), "No such item: " + itemId);
            }
        });
    }

    /** Hide an item from pickers and refuse new movements. Its history stays. */
    public void deactivateItem(String itemId) {
        setActive("item", itemId, false, "deactivate the item");
    }

    public void reactivateItem(String itemId) {
        setActive("item", itemId, true, "reactivate the item");
    }

    public Optional<Item> findItem(String itemId) {
        return read(connection -> list(connection, ITEM_SELECT + " WHERE id = ?", Inventory::mapItem, itemId))
                .stream().findFirst();
    }

    /** Every item, active or not, by name. */
    public List<Item> items() {
        return read(connection -> list(connection, ITEM_SELECT + " ORDER BY name", Inventory::mapItem));
    }

    // ============================================================ warehouses

    public Warehouse createWarehouse(String name, String address) {
        require("create a warehouse");
        Warehouse warehouse = new Warehouse(Ids.newIdString(),
                requireText(name, "A warehouse needs a name"), blankToNull(address), true);
        write("create the warehouse", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO warehouse (id, name, address, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, warehouse.id());
                ps.setString(2, warehouse.name());
                ps.setString(3, warehouse.address());
                ps.setLong(4, now());
                ps.setLong(5, now());
                ps.executeUpdate();
            }
        });
        return warehouse;
    }

    /**
     * Deactivate a warehouse. Refused while it holds stock: goods that exist
     * must stay somewhere a sale can take them from. Transfer or adjust first.
     */
    public void deactivateWarehouse(String warehouseId) {
        require("deactivate a warehouse");
        write("deactivate the warehouse", connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM stock WHERE warehouse_id = ? AND quantity_e4 > 0)")) {
                ps.setString(1, warehouseId);
                try (ResultSet rows = ps.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) == 1) {
                        throw new InventoryException("This warehouse still holds stock. Transfer or "
                                + "adjust it out before deactivating the warehouse.");
                    }
                }
            }
            updateActive(connection, "warehouse", warehouseId, false);
        });
    }

    public void reactivateWarehouse(String warehouseId) {
        setActive("warehouse", warehouseId, true, "reactivate the warehouse");
    }

    public List<Warehouse> warehouses() {
        return read(connection -> list(connection,
                "SELECT id, name, address, is_active FROM warehouse ORDER BY name",
                rows -> new Warehouse(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getInt(4) == 1)));
    }

    // ============================================================= movements

    /**
     * Record a stock movement and update quantities on hand, atomically.
     *
     * @return the movement's id
     * @throws InventoryException if the item or a warehouse is unknown or
     *                            inactive, the item is a service, the quantity
     *                            has more decimals than its unit allows, or an
     *                            out-movement would take stock below zero
     */
    public String record(MovementRequest request) {
        Objects.requireNonNull(request, "request");
        require("record a stock movement");
        String[] id = new String[1];
        write("record the stock movement", connection -> id[0] = StockWriter.apply(connection, request, now()));
        return id[0];
    }

    /** Quantity on hand of one item in one warehouse; zero when never stocked. */
    public BigDecimal stockOf(String itemId, String warehouseId) {
        return read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT quantity_e4 FROM stock WHERE item_id = ? AND warehouse_id = ?")) {
                ps.setString(1, itemId);
                ps.setString(2, warehouseId);
                try (ResultSet rows = ps.executeQuery()) {
                    return MoneyMapper.fromStored(rows.next() ? rows.getLong(1) : 0, Scale.QUANTITY);
                }
            }
        });
    }

    /** Every non-zero stock position. */
    public List<StockLevel> stockLevels() {
        return read(connection -> list(connection,
                "SELECT item_id, warehouse_id, quantity_e4 FROM stock WHERE quantity_e4 > 0 "
                        + "ORDER BY item_id, warehouse_id",
                rows -> new StockLevel(rows.getString(1), rows.getString(2),
                        MoneyMapper.fromStored(rows.getLong(3), Scale.QUANTITY))));
    }

    /** An item's movements, oldest first. */
    public List<MovementRequest> movementsOf(String itemId) {
        return read(connection -> list(connection,
                "SELECT movement_type, item_id, from_warehouse_id, to_warehouse_id, quantity_e4, "
                        + "unit_cost_e4, movement_date, source_type, source_id, narration "
                        + "FROM stock_movement WHERE item_id = ? ORDER BY movement_date, created_at, rowid",
                rows -> {
                    long cost = rows.getLong(6);
                    boolean noCost = rows.wasNull();
                    return new MovementRequest(MovementType.valueOf(rows.getString(1)), rows.getString(2),
                            rows.getString(3), rows.getString(4),
                            MoneyMapper.fromStored(rows.getLong(5), Scale.QUANTITY),
                            noCost ? null : MoneyMapper.fromStored(cost, Scale.MONEY),
                            LocalDate.parse(rows.getString(7)), rows.getString(8), rows.getString(9),
                            rows.getString(10));
                }, itemId));
    }

    /**
     * Recompute every stock position from the movement log and report where
     * the cached quantity disagrees. Empty means the cache is sound.
     *
     * <p>I-4 allows a cached balance only if it can be rebuilt and verified;
     * this is the verification.
     */
    public List<StockDiscrepancy> verifyStock() {
        return read(connection -> list(connection, """
                WITH flows AS (
                    SELECT item_id, to_warehouse_id AS warehouse_id, quantity_e4 AS q
                    FROM stock_movement WHERE to_warehouse_id IS NOT NULL
                    UNION ALL
                    SELECT item_id, from_warehouse_id, -quantity_e4
                    FROM stock_movement WHERE from_warehouse_id IS NOT NULL
                ),
                logged AS (
                    SELECT item_id, warehouse_id, SUM(q) AS q FROM flows GROUP BY item_id, warehouse_id
                ),
                positions AS (
                    SELECT item_id, warehouse_id FROM logged
                    UNION
                    SELECT item_id, warehouse_id FROM stock
                )
                SELECT p.item_id, p.warehouse_id,
                       COALESCE(s.quantity_e4, 0) AS cached,
                       COALESCE(l.q, 0) AS logged
                FROM positions p
                LEFT JOIN stock s ON s.item_id = p.item_id AND s.warehouse_id = p.warehouse_id
                LEFT JOIN logged l ON l.item_id = p.item_id AND l.warehouse_id = p.warehouse_id
                WHERE COALESCE(s.quantity_e4, 0) <> COALESCE(l.q, 0)
                ORDER BY p.item_id, p.warehouse_id
                """,
                rows -> new StockDiscrepancy(rows.getString(1), rows.getString(2),
                        MoneyMapper.fromStored(rows.getLong(3), Scale.QUANTITY),
                        MoneyMapper.fromStored(rows.getLong(4), Scale.QUANTITY))));
    }

    // =============================================================== helpers

    private static final String ITEM_SELECT =
            "SELECT id, name, sku, kind, unit_id, category_id, hsn_sac, description, is_active FROM item";

    private static Item mapItem(ResultSet rows) throws SQLException {
        return new Item(rows.getString(1), rows.getString(2), rows.getString(3),
                ItemKind.valueOf(rows.getString(4)), rows.getString(5), rows.getString(6),
                rows.getString(7), rows.getString(8), rows.getInt(9) == 1);
    }

    private void require(String operation) {
        entitlement.requireWritable(InventoryModule.ID, operation);
    }

    private long now() {
        return clock.millis();
    }

    private void setActive(String table, String id, boolean active, String description) {
        require(description);
        write(description, connection -> updateActive(connection, table, id, active));
    }

    private void updateActive(Connection connection, String table, String id, boolean active)
            throws SQLException {
        // table is one of two literals chosen in this class, never caller input.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + table + " SET is_active = ?, updated_at = ? WHERE id = ?")) {
            ps.setInt(1, active ? 1 : 0);
            ps.setLong(2, now());
            ps.setString(3, id);
            requireOneRow(ps.executeUpdate(), "No such " + table + ": " + id);
        }
    }

    private static boolean hasMovements(Connection connection, String itemId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM stock_movement WHERE item_id = ?)")) {
            ps.setString(1, itemId);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private static void requireOneRow(int updated, String message) {
        if (updated == 0) {
            throw new InventoryException(message);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new InventoryException(message);
        }
        return value.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @FunctionalInterface
    private interface Write {
        void run(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rows) throws SQLException;
    }

    private void write(String description, Write work) {
        try {
            transactions.inTransaction(connection -> {
                work.run(connection);
                return null;
            });
        } catch (SQLException e) {
            throw new InventoryException("Could not " + description + ": " + friendly(e), e);
        }
    }

    private <T> T read(ModuleTransactions.Work<T> work) {
        try {
            return transactions.read(work);
        } catch (SQLException e) {
            throw new InventoryException("Could not read inventory: " + e.getMessage(), e);
        }
    }

    private static <T> List<T> list(Connection connection, String sql, RowMapper<T> mapper,
                                    String... parameters) throws SQLException {
        List<T> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    result.add(mapper.map(rows));
                }
            }
        }
        return result;
    }

    /** Turn the database's uniqueness refusal into a sentence. */
    private static String friendly(SQLException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("UNIQUE constraint failed")) {
            return "that name or code is already in use";
        }
        return message;
    }
}
