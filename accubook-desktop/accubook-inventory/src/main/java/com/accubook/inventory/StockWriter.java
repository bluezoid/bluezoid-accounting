package com.accubook.inventory;

import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Applies one movement inside a transaction the caller already holds.
 *
 * <p>Separate from {@link Inventory} so that a document posting in P7c can move
 * stock in the same transaction as its voucher - an invoice that posted but
 * did not move stock, or the reverse, must be impossible.
 *
 * <p>Order matters: the guarded decrement runs <em>before</em> the movement
 * row is written, so a refused issue leaves no trace even before rollback.
 */
final class StockWriter {

    private StockWriter() {
    }

    static String apply(Connection connection, MovementRequest request, long now) throws SQLException {
        ItemFacts item = itemFacts(connection, request.itemId());
        if (!item.active) {
            throw new InventoryException("\"" + item.name + "\" is inactive; reactivate it to move stock");
        }
        if (item.service) {
            throw new InventoryException("\"" + item.name + "\" is a service and has no stock");
        }
        long quantity = toStoredQuantity(request.quantity(), item);

        if (request.fromWarehouseId() != null) {
            requireActiveWarehouse(connection, request.fromWarehouseId());
            decrement(connection, request.itemId(), request.fromWarehouseId(), quantity, item, request, now);
        }
        if (request.toWarehouseId() != null) {
            requireActiveWarehouse(connection, request.toWarehouseId());
            increment(connection, request.itemId(), request.toWarehouseId(), quantity, now);
        }

        String id = Ids.newIdString();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO stock_movement (id, item_id, movement_type, from_warehouse_id, "
                        + "to_warehouse_id, quantity_e4, unit_cost_e4, movement_date, source_type, "
                        + "source_id, narration, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, request.itemId());
            ps.setString(3, request.type().name());
            ps.setString(4, request.fromWarehouseId());
            ps.setString(5, request.toWarehouseId());
            ps.setLong(6, quantity);
            if (request.unitCost() == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setLong(7, MoneyMapper.toStored(request.unitCost(), Scale.MONEY));
            }
            ps.setString(8, request.date().toString());
            ps.setString(9, request.sourceType());
            ps.setString(10, request.sourceId());
            ps.setString(11, request.narration());
            ps.setLong(12, now);
            ps.executeUpdate();
        }
        return id;
    }

    /**
     * The guarded decrement. One statement decides: if fewer than
     * {@code quantity} are on hand, no row matches and nothing changes.
     */
    private static void decrement(Connection connection, String itemId, String warehouseId, long quantity,
                                  ItemFacts item, MovementRequest request, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE stock SET quantity_e4 = quantity_e4 - ?, updated_at = ? "
                        + "WHERE item_id = ? AND warehouse_id = ? AND quantity_e4 >= ?")) {
            ps.setLong(1, quantity);
            ps.setLong(2, now);
            ps.setString(3, itemId);
            ps.setString(4, warehouseId);
            ps.setLong(5, quantity);
            if (ps.executeUpdate() == 0) {
                BigDecimal onHand = onHand(connection, itemId, warehouseId);
                throw new InventoryException("Only " + onHand.stripTrailingZeros().toPlainString() + " "
                        + item.unitSymbol + " of \"" + item.name + "\" on hand in "
                        + warehouseName(connection, warehouseId) + "; cannot take out "
                        + request.quantity().stripTrailingZeros().toPlainString()
                        + ". Stock may not go below zero.");
            }
        }
    }

    private static void increment(Connection connection, String itemId, String warehouseId, long quantity,
                                  long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO stock (item_id, warehouse_id, quantity_e4, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (item_id, warehouse_id) DO UPDATE "
                        + "SET quantity_e4 = quantity_e4 + excluded.quantity_e4, updated_at = excluded.updated_at")) {
            ps.setString(1, itemId);
            ps.setString(2, warehouseId);
            ps.setLong(3, quantity);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    /**
     * Scale check against the unit: 2.5 pieces is a data-entry error, 2.5 kg is
     * not. Rejected rather than rounded - rounding a quantity silently changes
     * what was bought or sold.
     */
    private static long toStoredQuantity(BigDecimal quantity, ItemFacts item) {
        BigDecimal normalised = quantity.stripTrailingZeros();
        if (normalised.scale() > item.unitDecimals) {
            throw new InventoryException("\"" + item.name + "\" is counted in " + item.unitSymbol
                    + ", which allows " + item.unitDecimals + " decimal place"
                    + (item.unitDecimals == 1 ? "" : "s") + "; "
                    + quantity.toPlainString() + " has more");
        }
        return MoneyMapper.toStored(normalised, Scale.QUANTITY);
    }

    private record ItemFacts(String name, boolean active, boolean service, String unitSymbol,
                             int unitDecimals) {
    }

    private static ItemFacts itemFacts(Connection connection, String itemId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT i.name, i.is_active, i.kind, u.symbol, u.decimals "
                        + "FROM item i JOIN unit u ON u.id = i.unit_id WHERE i.id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new InventoryException("No such item: " + itemId);
                }
                return new ItemFacts(rows.getString(1), rows.getInt(2) == 1,
                        "SERVICE".equals(rows.getString(3)), rows.getString(4), rows.getInt(5));
            }
        }
    }

    private static void requireActiveWarehouse(Connection connection, String warehouseId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT name, is_active FROM warehouse WHERE id = ?")) {
            ps.setString(1, warehouseId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new InventoryException("No such warehouse: " + warehouseId);
                }
                if (rows.getInt(2) == 0) {
                    throw new InventoryException("The warehouse \"" + rows.getString(1)
                            + "\" is inactive; reactivate it to move stock in or out");
                }
            }
        }
    }

    private static BigDecimal onHand(Connection connection, String itemId, String warehouseId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT quantity_e4 FROM stock WHERE item_id = ? AND warehouse_id = ?")) {
            ps.setString(1, itemId);
            ps.setString(2, warehouseId);
            try (ResultSet rows = ps.executeQuery()) {
                return MoneyMapper.fromStored(rows.next() ? rows.getLong(1) : 0, Scale.QUANTITY);
            }
        }
    }

    private static String warehouseName(Connection connection, String warehouseId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM warehouse WHERE id = ?")) {
            ps.setString(1, warehouseId);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? "\"" + rows.getString(1) + "\"" : "that warehouse";
            }
        }
    }
}
