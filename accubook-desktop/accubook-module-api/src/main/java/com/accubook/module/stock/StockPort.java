package com.accubook.module.stock;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Stock as other modules see it - without depending on the inventory module.
 *
 * <p>P4 forbids one feature module requiring another. Sales and purchase
 * documents must still move stock (P7-5), so they reach inventory through this
 * port, which {@code accubook.inventory} provides as a service. In a build
 * without inventory, {@link #installed()} is empty and a document line naming
 * an item is refused.
 *
 * <p>Every method works inside the caller's transaction, so a document, its
 * voucher and its stock movement commit or roll back as one. The port does not
 * check entitlement: the caller asks whether the inventory module is writable
 * before using it, and the persistence guard refuses inventory tables anyway.
 */
public interface StockPort {

    /** The stock effects a document can have. */
    enum Kind {
        PURCHASE, SALES_RETURN, ADJUSTMENT_IN,
        SALE, PURCHASE_RETURN, ADJUSTMENT_OUT;

        public boolean bringsStockIn() {
            return this == PURCHASE || this == SALES_RETURN || this == ADJUSTMENT_IN;
        }
    }

    /** What a document needs to know about an item. */
    record ItemFacts(String id, String name, boolean goods, boolean active, String unitSymbol, int unitDecimals) {
    }

    Optional<ItemFacts> item(Connection connection, String itemId) throws SQLException;

    /**
     * An item's GST on a date (P9-2), as the defaults a document line copies.
     *
     * @param category one of {@code TAXABLE}, {@code NIL_RATED}, {@code EXEMPT},
     *                 {@code NON_GST} - named in the kernel tax module, which
     *                 this API does not depend on
     * @param gstRate  percent, zero unless taxable
     * @param hsnSac   the item's HSN or SAC code, or null
     */
    record ItemTax(LocalDate effectiveFrom, String category, BigDecimal gstRate, BigDecimal cessRate, String hsnSac) {
    }

    /** The item's GST in force on {@code date}; empty if none has been recorded from that date or earlier. */
    Optional<ItemTax> taxOn(Connection connection, String itemId, LocalDate date) throws SQLException;

    /**
     * Record one movement and update quantities on hand.
     *
     * @param warehouseId    receiving warehouse for an incoming kind, issuing
     *                       warehouse for an outgoing one
     * @param costSourceType with {@code costSourceId}: the document whose stock
     *                       movements this one undoes (a credit note's invoice,
     *                       a cancelled bill), so it is valued at their cost.
     *                       Both null when it undoes nothing.
     * @throws RuntimeException if refused - e.g. it would take stock below zero
     */
    void move(Connection connection, Kind kind, String itemId, String warehouseId, BigDecimal quantity,
              BigDecimal unitCost, LocalDate date, String sourceType, String sourceId, String narration,
              String costSourceType, String costSourceId) throws SQLException;

    /** The provider in this build, if the build contains inventory. */
    static Optional<StockPort> installed() {
        return ServiceLoader.load(StockPort.class).findFirst();
    }
}
