package com.accubook.inventory;

import com.accubook.module.stock.StockPort;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Inventory's side of {@link StockPort}: the same {@link StockWriter} the
 * inventory use cases use, so a document's movement obeys exactly the same
 * rules - guarded decrement, unit precision, active item and warehouse.
 *
 * <p>Public with a no-argument constructor because {@code ServiceLoader}
 * instantiates it.
 */
public final class InventoryStockPort implements StockPort {

    @Override
    public Optional<ItemFacts> item(Connection connection, String itemId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT i.id, i.name, i.kind, i.is_active, u.symbol, u.decimals, i.hsn_sac "
                        + "FROM item i JOIN unit u ON u.id = i.unit_id WHERE i.id = ?")) {
            ps.setString(1, itemId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ItemFacts(rows.getString(1), rows.getString(2),
                        "GOODS".equals(rows.getString(3)), rows.getInt(4) == 1,
                        rows.getString(5), rows.getInt(6), rows.getString(7)));
            }
        }
    }

    @Override
    public Optional<ItemTax> taxOn(Connection connection, String itemId, LocalDate date) throws SQLException {
        return ItemTaxes.on(connection, itemId, date).map(t -> new ItemTax(t.effectiveFrom(), t.category().name(),
                t.gstRate(), t.cessRate(), t.hsnSac()));
    }

    @Override
    public void move(Connection connection, Kind kind, String itemId, String warehouseId, BigDecimal quantity,
                     BigDecimal unitCost, LocalDate date, String sourceType, String sourceId, String narration,
                     String costSourceType, String costSourceId) throws SQLException {
        MovementType type = MovementType.valueOf(kind.name());
        MovementRequest request = new MovementRequest(type, itemId,
                kind.bringsStockIn() ? null : warehouseId,
                kind.bringsStockIn() ? warehouseId : null,
                quantity, kind.bringsStockIn() ? unitCost : null, date, sourceType, sourceId, narration,
                costSourceType, costSourceId);
        StockWriter.apply(connection, request, System.currentTimeMillis());
    }
}
