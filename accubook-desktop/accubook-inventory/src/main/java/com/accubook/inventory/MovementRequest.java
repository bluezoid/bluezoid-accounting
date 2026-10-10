package com.accubook.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One stock movement to record.
 *
 * @param unitCost   what the source document said a unit cost; recorded for
 *                   valuation in P8 and read by nothing in P7. Optional.
 * @param sourceType with {@code sourceId}, the document that caused this
 *                   movement; both or neither
 * @param costSourceType with {@code costSourceId}, the document whose movements
 *                   this one undoes - a credit note's original invoice, a
 *                   cancelled bill - so it is valued at their cost (P8)
 */
public record MovementRequest(
        MovementType type,
        String itemId,
        String fromWarehouseId,
        String toWarehouseId,
        BigDecimal quantity,
        BigDecimal unitCost,
        LocalDate date,
        String sourceType,
        String sourceId,
        String narration,
        String costSourceType,
        String costSourceId) {

    /** A movement that undoes nothing. */
    public MovementRequest(MovementType type, String itemId, String fromWarehouseId, String toWarehouseId,
                           BigDecimal quantity, BigDecimal unitCost, LocalDate date, String sourceType,
                           String sourceId, String narration) {
        this(type, itemId, fromWarehouseId, toWarehouseId, quantity, unitCost, date, sourceType, sourceId,
                narration, null, null);
    }

    public MovementRequest {
        if ((costSourceType == null) != (costSourceId == null)) {
            throw new InventoryException("A cost source needs both a type and an id");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(date, "date");
        if (quantity.signum() <= 0) {
            throw new InventoryException("A movement must move a positive quantity");
        }
        if (unitCost != null && unitCost.signum() < 0) {
            throw new InventoryException("A unit cost cannot be negative");
        }
        if ((sourceType == null) != (sourceId == null)) {
            throw new InventoryException("A movement's source needs both a type and an id");
        }
        if (type.needsSource() != (fromWarehouseId != null)
                || type.needsDestination() != (toWarehouseId != null)) {
            throw new InventoryException(switch (type.direction()) {
                case IN -> type + " brings stock in: name the receiving warehouse only";
                case OUT -> type + " takes stock out: name the issuing warehouse only";
                case TRANSFER -> "A transfer needs both a source and a destination warehouse";
            });
        }
        if (type == MovementType.TRANSFER && fromWarehouseId.equals(toWarehouseId)) {
            throw new InventoryException("A transfer's source and destination must differ");
        }
    }

    /** Stock coming into a warehouse. */
    public static MovementRequest in(MovementType type, String itemId, String warehouseId,
                                     BigDecimal quantity, BigDecimal unitCost, LocalDate date,
                                     String narration) {
        return new MovementRequest(type, itemId, null, warehouseId, quantity, unitCost, date,
                null, null, narration);
    }

    /** Stock leaving a warehouse. */
    public static MovementRequest out(MovementType type, String itemId, String warehouseId,
                                      BigDecimal quantity, LocalDate date, String narration) {
        return new MovementRequest(type, itemId, warehouseId, null, quantity, null, date,
                null, null, narration);
    }

    public static MovementRequest transfer(String itemId, String fromWarehouseId, String toWarehouseId,
                                           BigDecimal quantity, LocalDate date, String narration) {
        return new MovementRequest(MovementType.TRANSFER, itemId, fromWarehouseId, toWarehouseId,
                quantity, null, date, null, null, narration);
    }
}
