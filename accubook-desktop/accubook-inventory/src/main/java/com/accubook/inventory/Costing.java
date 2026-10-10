package com.accubook.inventory;

import com.accubook.platform.id.Ids;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives the valuation ledger from the movement log (Phase 8, periodic 5a).
 *
 * <p>The unit of work is <strong>an item, across all its warehouses, from a
 * date</strong>: every derived row for that item on or after the date is
 * removed and its movements are replayed in costing order (date, then entry
 * order). Replaying the whole item - not one warehouse - means a transfer's
 * incoming value is always the outgoing value just computed for it, with no
 * cascade between warehouses that could loop.
 *
 * <h2>Values</h2>
 * <ul>
 *   <li>In at a stated unit cost: purchase, opening, a counted gain.</li>
 *   <li>In with no cost: at the warehouse's current average (P8-5).</li>
 *   <li>Out: weighted average (moving, P8-2) or FIFO with a consumption record
 *       per layer (P8-1).</li>
 *   <li>Transfer: in at exactly the value that went out - no value created or
 *       lost, no phantom layer (D-31).</li>
 *   <li>Undoing an earlier document (a return, a cancellation - the movement's
 *       cost source): at the unit cost of that document's movements of the
 *       item.</li>
 * </ul>
 *
 * <p>All amounts are integer minor units (e4); every division rounds half-up
 * once, and taking the whole of a position or layer takes its exact value, so
 * nothing drifts: a warehouse emptied is worth exactly zero.
 */
final class Costing {

    private Costing() {
    }

    /** Re-derive {@code itemId} from {@code from}, or from earlier if older movements were never costed. */
    static void recost(Connection connection, String itemId, LocalDate from) throws SQLException {
        LocalDate start = earliestUncosted(connection, itemId).filter(d -> d.isBefore(from)).orElse(from);
        removeFrom(connection, itemId, start);

        boolean fifo = "FIFO".equals(single(connection, "SELECT costing_method FROM item WHERE id = ?", itemId));
        Map<String, long[]> positions = openingPositions(connection, itemId, start);

        try (PreparedStatement ps = connection.prepareStatement("SELECT rowid, id, movement_type, from_warehouse_id, "
                + "to_warehouse_id, quantity_e4, unit_cost_e4, movement_date, cost_source_type, cost_source_id "
                + "FROM stock_movement WHERE item_id = ? AND movement_date >= ? ORDER BY movement_date, rowid")) {
            ps.setString(1, itemId);
            ps.setString(2, start.toString());
            List<Object[]> movements = new ArrayList<>();
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    long cost = rows.getLong(7);
                    boolean noCost = rows.wasNull();
                    movements.add(new Object[]{rows.getLong(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getLong(6), noCost ? null : cost,
                            rows.getString(8), rows.getString(9), rows.getString(10)});
                }
            }
            for (Object[] m : movements) {
                apply(connection, itemId, fifo, positions, (Long) m[0], (String) m[1], (String) m[2],
                        (String) m[3], (String) m[4], (Long) m[5], (Long) m[6], LocalDate.parse((String) m[7]),
                        (String) m[8], (String) m[9]);
            }
        }
    }

    private static void apply(Connection connection, String itemId, boolean fifo, Map<String, long[]> positions,
                              long order, String movementId, String type, String from, String to, long quantity,
                              Long unitCost, LocalDate date, String costSourceType, String costSourceId)
            throws SQLException {
        Long sourceUnitCost = costSourceType == null ? null
                : sourceUnitCost(connection, itemId, costSourceType, costSourceId);
        long outValue = 0;
        if (from != null) {
            long[] position = positions.computeIfAbsent(from, w -> new long[2]);
            String entryId = Ids.newIdString();
            FifoDraw draw = null;
            if (fifo) {
                draw = planFifo(connection, itemId, from, quantity,
                        sourceLayerHint(connection, itemId, from, costSourceType, costSourceId));
                outValue = draw.value();
            } else if (sourceUnitCost != null && !"TRANSFER".equals(type)) {
                // Returning goods to a supplier, or cancelling a bill: at what they came in at,
                // never more than is on the books.
                outValue = Math.min(position[1], times(quantity, sourceUnitCost));
            } else {
                outValue = quantity == position[0] ? position[1] : share(position[1], quantity, position[0]);
            }
            position[0] -= quantity;
            position[1] -= outValue;
            if (position[0] == 0) {
                // Whatever rounding left behind belongs to what went out.
                outValue += position[1];
                position[1] = 0;
            }
            insertEntry(connection, entryId, movementId, itemId, from, date, order, -quantity, -outValue, position);
            if (draw != null) {
                recordConsumption(connection, entryId, draw);
            }
        }
        if (to != null) {
            long[] position = positions.computeIfAbsent(to, w -> new long[2]);
            long inValue;
            if ("TRANSFER".equals(type)) {
                inValue = outValue;
            } else if (sourceUnitCost != null) {
                inValue = times(quantity, sourceUnitCost);
            } else if (unitCost != null) {
                inValue = times(quantity, unitCost);
            } else {
                // P8-5: a gain with no stated cost comes in at the current average.
                inValue = position[0] == 0 ? 0 : share(position[1], quantity, position[0]);
            }
            position[0] += quantity;
            position[1] += inValue;
            String entryId = Ids.newIdString();
            insertEntry(connection, entryId, movementId, itemId, to, date, order, quantity, inValue, position);
            if (fifo) {
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO fifo_layer (id, entry_id, item_id, "
                        + "warehouse_id, layer_date, layer_order, quantity_e4, value_e4) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, Ids.newIdString());
                    ps.setString(2, entryId);
                    ps.setString(3, itemId);
                    ps.setString(4, to);
                    ps.setString(5, date.toString());
                    ps.setLong(6, order);
                    ps.setLong(7, quantity);
                    ps.setLong(8, inValue);
                    ps.executeUpdate();
                }
            }
        }
    }

    private record FifoDraw(long value, List<String> layerIds, List<long[]> taken) {
    }

    /**
     * FIFO issue: oldest open layers first. A layer named by the cost source -
     * the bill a purchase return sends goods back from - is drawn first, so the
     * return leaves at what those goods cost. Planned before the entry exists;
     * recorded after, since consumption references it.
     */
    private static FifoDraw planFifo(Connection connection, String itemId, String warehouseId, long quantity,
                                     String preferredLayerId) throws SQLException {
        List<long[]> taken = new ArrayList<>();
        List<String> layerIds = new ArrayList<>();
        long needed = quantity;
        long value = 0;
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT l.id, l.quantity_e4, l.value_e4,
                       COALESCE((SELECT SUM(c.quantity_e4) FROM fifo_consumption c WHERE c.layer_id = l.id), 0),
                       COALESCE((SELECT SUM(c.value_e4) FROM fifo_consumption c WHERE c.layer_id = l.id), 0)
                FROM fifo_layer l WHERE l.item_id = ? AND l.warehouse_id = ?
                ORDER BY CASE WHEN l.id = ? THEN 0 ELSE 1 END, l.layer_date, l.layer_order
                """)) {
            ps.setString(1, itemId);
            ps.setString(2, warehouseId);
            ps.setString(3, preferredLayerId);
            try (ResultSet rows = ps.executeQuery()) {
                while (needed > 0 && rows.next()) {
                    long layerQuantity = rows.getLong(2);
                    long layerValue = rows.getLong(3);
                    long remaining = layerQuantity - rows.getLong(4);
                    long remainingValue = layerValue - rows.getLong(5);
                    if (remaining <= 0) {
                        continue;
                    }
                    long take = Math.min(remaining, needed);
                    long takeValue = take == remaining ? remainingValue : share(layerValue, take, layerQuantity);
                    layerIds.add(rows.getString(1));
                    taken.add(new long[]{take, takeValue});
                    needed -= take;
                    value += takeValue;
                }
            }
        }
        if (needed > 0) {
            // Stock never goes negative (P7-4), so the layers always cover an issue.
            throw new IllegalStateException("FIFO layers do not cover an issue of item " + itemId);
        }
        return new FifoDraw(value, layerIds, taken);
    }

    private static void recordConsumption(Connection connection, String entryId, FifoDraw draw) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO fifo_consumption (id, entry_id, "
                + "layer_id, quantity_e4, value_e4) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < draw.taken().size(); i++) {
                insert.setString(1, Ids.newIdString());
                insert.setString(2, entryId);
                insert.setString(3, draw.layerIds().get(i));
                insert.setLong(4, draw.taken().get(i)[0]);
                insert.setLong(5, draw.taken().get(i)[1]);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void insertEntry(Connection connection, String entryId, String movementId, String itemId,
                                    String warehouseId, LocalDate date, long order, long quantity, long value,
                                    long[] after) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO valuation_entry (id, movement_id, item_id, "
                + "warehouse_id, entry_date, entry_order, quantity_e4, value_e4, quantity_after_e4, value_after_e4) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, entryId);
            ps.setString(2, movementId);
            ps.setString(3, itemId);
            ps.setString(4, warehouseId);
            ps.setString(5, date.toString());
            ps.setLong(6, order);
            ps.setLong(7, quantity);
            ps.setLong(8, value);
            ps.setLong(9, after[0]);
            ps.setLong(10, after[1]);
            ps.executeUpdate();
        }
    }

    /** Unit cost (e4 per unit) of the cost source's movements of this item, from their valuation entries. */
    private static Long sourceUnitCost(Connection connection, String itemId, String sourceType, String sourceId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT SUM(ABS(e.value_e4)), SUM(ABS(e.quantity_e4)) "
                + "FROM valuation_entry e JOIN stock_movement m ON m.id = e.movement_id "
                + "WHERE m.item_id = ? AND m.source_type = ? AND m.source_id = ?")) {
            ps.setString(1, itemId);
            ps.setString(2, sourceType);
            ps.setString(3, sourceId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next() || rows.getLong(2) == 0) {
                    return null;
                }
                return BigDecimal.valueOf(rows.getLong(1)).multiply(BigDecimal.valueOf(10_000))
                        .divide(BigDecimal.valueOf(rows.getLong(2)), 0, RoundingMode.HALF_UP).longValueExact();
            }
        }
    }

    private static String sourceLayerHint(Connection connection, String itemId, String warehouseId,
                                          String sourceType, String sourceId) throws SQLException {
        if (sourceType == null) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT l.id FROM fifo_layer l "
                + "JOIN valuation_entry e ON e.id = l.entry_id JOIN stock_movement m ON m.id = e.movement_id "
                + "WHERE l.item_id = ? AND l.warehouse_id = ? AND m.source_type = ? AND m.source_id = ? LIMIT 1")) {
            ps.setString(1, itemId);
            ps.setString(2, warehouseId);
            ps.setString(3, sourceType);
            ps.setString(4, sourceId);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static void removeFrom(Connection connection, String itemId, LocalDate start) throws SQLException {
        String later = "(SELECT id FROM valuation_entry WHERE item_id = ? AND entry_date >= ?)";
        for (String sql : List.of(
                "DELETE FROM fifo_consumption WHERE entry_id IN " + later,
                "DELETE FROM fifo_consumption WHERE layer_id IN (SELECT id FROM fifo_layer WHERE entry_id IN " + later + ")",
                "DELETE FROM fifo_layer WHERE entry_id IN " + later,
                "DELETE FROM valuation_entry WHERE item_id = ? AND entry_date >= ?")) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, itemId);
                ps.setString(2, start.toString());
                ps.executeUpdate();
            }
        }
    }

    private static Map<String, long[]> openingPositions(Connection connection, String itemId, LocalDate start)
            throws SQLException {
        Map<String, long[]> positions = new HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT e.warehouse_id, e.quantity_after_e4, e.value_after_e4 FROM valuation_entry e
                WHERE e.item_id = ? AND e.entry_date < ?
                  AND NOT EXISTS (SELECT 1 FROM valuation_entry n
                                  WHERE n.item_id = e.item_id AND n.warehouse_id = e.warehouse_id
                                    AND n.entry_date < ?
                                    AND (n.entry_date > e.entry_date
                                         OR (n.entry_date = e.entry_date AND n.entry_order > e.entry_order)
                                         OR (n.entry_date = e.entry_date AND n.entry_order = e.entry_order
                                             AND n.quantity_e4 > e.quantity_e4)))
                """)) {
            ps.setString(1, itemId);
            ps.setString(2, start.toString());
            ps.setString(3, start.toString());
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    positions.put(rows.getString(1), new long[]{rows.getLong(2), rows.getLong(3)});
                }
            }
        }
        return positions;
    }

    private static java.util.Optional<LocalDate> earliestUncosted(Connection connection, String itemId)
            throws SQLException {
        String date = single(connection, "SELECT MIN(m.movement_date) FROM stock_movement m WHERE m.item_id = ? "
                + "AND NOT EXISTS (SELECT 1 FROM valuation_entry e WHERE e.movement_id = m.id)", itemId);
        return date == null ? java.util.Optional.empty() : java.util.Optional.of(LocalDate.parse(date));
    }

    private static String single(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** quantity (e4) x unit cost (e4 per unit) -> value (e4), half-up. */
    static long times(long quantityE4, long unitCostE4) {
        return BigDecimal.valueOf(quantityE4).multiply(BigDecimal.valueOf(unitCostE4))
                .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** value x part / whole, half-up. */
    static long share(long value, long part, long whole) {
        return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP).longValueExact();
    }
}
