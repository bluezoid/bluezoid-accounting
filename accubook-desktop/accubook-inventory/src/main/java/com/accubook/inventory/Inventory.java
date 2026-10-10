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
    private final com.accubook.accounting.posting.TransactionalPosting posting;

    public Inventory(ModuleTransactions transactions, EntitlementGuard entitlement, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.posting = new com.accubook.accounting.posting.TransactionalPosting(entitlement, clock);
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
                blankToNull(description), true, CostingMethod.WEIGHTED_AVERAGE);
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

    // ============================================================= valuation

    /**
     * P8-1: choose how an item is costed. Refused once it has moved - every
     * past issue was costed under the old method (the schema refuses it too).
     */
    public void setCostingMethod(String itemId, CostingMethod method) {
        Objects.requireNonNull(method, "method");
        require("change an item's costing method");
        write("change the costing method", connection -> {
            if (hasMovements(connection, itemId)) {
                throw new InventoryException("This item already has stock movements, so its costing method "
                        + "cannot change: every issue so far was costed the old way.");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE item SET costing_method = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, method.name());
                ps.setLong(2, now());
                ps.setString(3, itemId);
                requireOneRow(ps.executeUpdate(), "No such item: " + itemId);
            }
        });
    }

    /**
     * Every stock position's value on {@code asOf}, read from the valuation
     * ledger - the position after the last entry on or before that date. Never
     * today's quantities at old costs (D-24).
     */
    public StockValuation valuation(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return read(connection -> {
            List<ValuationLine> lines = list(connection, POSITIONS_AS_OF + " ORDER BY e.item_id, e.warehouse_id",
                    rows -> new ValuationLine(rows.getString(1), rows.getString(2),
                            MoneyMapper.fromStored(rows.getLong(3), Scale.QUANTITY),
                            MoneyMapper.fromStored(rows.getLong(4), Scale.MONEY)),
                    asOf.toString(), asOf.toString());
            BigDecimal total = lines.stream().map(ValuationLine::value).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new StockValuation(asOf, lines.stream().filter(l -> l.quantity().signum() > 0).toList(), total);
        });
    }

    /**
     * P8-4: value all stock on {@code date} and post the difference from the
     * Stock-in-Hand balance on that date, rounded to the paisa (P8-6), as a
     * closing-stock voucher:
     * an increase Dr Stock-in-Hand / Cr Change in Stock, a decrease the reverse.
     * Change in Stock sits in Direct Expenses, so gross profit is
     * opening + purchases − closing.
     *
     * <p>A run replaces any earlier run for the same date. Because it changes the
     * Stock-in-Hand balance from {@code date} onwards, any posted run after it is
     * marked stale and must be posted again, in date order (P8-3).
     */
    public ValuationRun postValuation(LocalDate date) {
        Objects.requireNonNull(date, "date");
        require("post a stock valuation");
        String runId = Ids.newIdString();
        write("post the stock valuation", connection -> {
            String fiscalYearId = openFiscalYear(connection, date);
            costEverythingUncosted(connection);

            long value;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COALESCE(SUM(value_after_e4), 0) FROM (" + POSITIONS_AS_OF + ")")) {
                ps.setString(1, date.toString());
                ps.setString(2, date.toString());
                try (ResultSet rows = ps.executeQuery()) {
                    rows.next();
                    value = rows.getLong(1);
                }
            }
            String stockLedger = systemLedger(connection, "STOCK_IN_HAND", "Stock-in-Hand", "STOCK_IN_HAND");
            String changeLedger = systemLedger(connection, "CHANGE_IN_STOCK", "Change in Stock", "DIRECT_EXPENSES");
            long balance;
            try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(e.debit_amount_e4 - "
                    + "e.credit_amount_e4), 0) FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id "
                    + "WHERE e.ledger_id = ? AND v.is_posted = 1 AND v.voucher_date <= ?")) {
                ps.setString(1, stockLedger);
                ps.setString(2, date.toString());
                try (ResultSet rows = ps.executeQuery()) {
                    rows.next();
                    balance = rows.getLong(1);
                }
            }

            // P8-6: the books carry whole paise. The valuation keeps every digit;
            // the voucher posts the value rounded half-up to the paisa. Each run
            // posts against the ledger balance, so the sub-paisa difference never
            // accumulates.
            long postedValue = Math.floorDiv(value + 50, 100) * 100;
            String voucherId = null;
            long delta = postedValue - balance;
            if (delta != 0) {
                BigDecimal amount = MoneyMapper.fromStored(Math.abs(delta), Scale.MONEY);
                List<com.accubook.accounting.posting.EntryLine> entries = delta > 0
                        ? List.of(com.accubook.accounting.posting.EntryLine.debit(stockLedger, amount),
                                  com.accubook.accounting.posting.EntryLine.credit(changeLedger, amount))
                        : List.of(com.accubook.accounting.posting.EntryLine.debit(changeLedger, amount),
                                  com.accubook.accounting.posting.EntryLine.credit(stockLedger, amount));
                String journal;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT id FROM voucher_type WHERE code = 'JOURNAL'");
                     ResultSet rows = ps.executeQuery()) {
                    rows.next();
                    journal = rows.getString(1);
                }
                voucherId = posting.postWithin(connection, new com.accubook.accounting.posting.PostingRequest(
                        InventoryModule.ID, fiscalYearId, journal, date, null,
                        "Closing stock valuation as on " + date, entries, "valuation:" + runId)).voucherId();
            }

            for (String sql : List.of(
                    "UPDATE valuation_run SET status = 'SUPERSEDED' WHERE run_date = ? AND status <> 'SUPERSEDED'",
                    "UPDATE valuation_run SET status = 'STALE' WHERE run_date > ? AND status = 'CURRENT'")) {
                if (delta == 0 && sql.contains("STALE")) {
                    continue;
                }
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, date.toString());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO valuation_run (id, run_date, value_e4, "
                    + "voucher_id, status, created_at) VALUES (?, ?, ?, ?, 'CURRENT', ?)")) {
                ps.setString(1, runId);
                ps.setString(2, date.toString());
                ps.setLong(3, value);
                ps.setString(4, voucherId);
                ps.setLong(5, now());
                ps.executeUpdate();
            }
        });
        return valuationRuns().stream().filter(r -> r.id().equals(runId)).findFirst().orElseThrow();
    }

    /** Every valuation run, newest date first. */
    public List<ValuationRun> valuationRuns() {
        return read(connection -> list(connection, "SELECT id, run_date, value_e4, voucher_id, status FROM valuation_run "
                        + "ORDER BY run_date DESC, created_at DESC",
                rows -> new ValuationRun(rows.getString(1), LocalDate.parse(rows.getString(2)),
                        MoneyMapper.fromStored(rows.getLong(3), Scale.MONEY), rows.getString(4),
                        ValuationRun.Status.valueOf(rows.getString(5)))));
    }

    /** The last entry on or before a date for every item and warehouse that has one. */
    private static final String POSITIONS_AS_OF = """
            SELECT e.item_id, e.warehouse_id, e.quantity_after_e4, e.value_after_e4
            FROM valuation_entry e
            WHERE e.entry_date <= ?
              AND NOT EXISTS (SELECT 1 FROM valuation_entry n
                              WHERE n.item_id = e.item_id AND n.warehouse_id = e.warehouse_id
                                AND n.entry_date <= ?
                                AND (n.entry_date > e.entry_date
                                     OR (n.entry_date = e.entry_date AND n.entry_order > e.entry_order)))
            """;

    /** Movements recorded before costing existed are costed before anything is valued. */
    private static void costEverythingUncosted(Connection connection) throws SQLException {
        List<String[]> pending = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT m.item_id, MIN(m.movement_date) "
                + "FROM stock_movement m WHERE NOT EXISTS (SELECT 1 FROM valuation_entry e WHERE e.movement_id = m.id) "
                + "GROUP BY m.item_id");
             ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                pending.add(new String[]{rows.getString(1), rows.getString(2)});
            }
        }
        for (String[] item : pending) {
            Costing.recost(connection, item[0], LocalDate.parse(item[1]));
        }
    }

    private static String openFiscalYear(Connection connection, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, is_closed FROM fiscal_year "
                + "WHERE start_date <= ? AND end_date >= ?")) {
            ps.setString(1, date.toString());
            ps.setString(2, date.toString());
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new InventoryException("No fiscal year covers " + date);
                }
                if (rows.getInt(2) == 1) {
                    throw new InventoryException("The fiscal year containing " + date + " is closed");
                }
                return rows.getString(1);
            }
        }
    }

    /**
     * The ledger a valuation posts to, created the first time it is needed:
     * a build without inventory never shows it (P2-1). If a user ledger already
     * has the name, the system ledger takes a distinct one.
     */
    private String systemLedger(Connection connection, String code, String name, String groupCode)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM ledger WHERE system_code = ?")) {
            ps.setString(1, code);
            try (ResultSet rows = ps.executeQuery()) {
                if (rows.next()) {
                    return rows.getString(1);
                }
            }
        }
        String id = Ids.newIdString();
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ledger (id, group_id, system_code, name, "
                + "is_system, created_at, updated_at) SELECT ?, g.id, ?, CASE WHEN EXISTS (SELECT 1 FROM ledger "
                + "WHERE lower(name) = lower(?)) THEN ? || ' (AccuBook)' ELSE ? END, 1, ?, ? "
                + "FROM ledger_group g WHERE g.system_code = ?")) {
            ps.setString(1, id);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, name);
            ps.setString(5, name);
            ps.setLong(6, now());
            ps.setLong(7, now());
            ps.setString(8, groupCode);
            if (ps.executeUpdate() == 0) {
                throw new InventoryException("The " + groupCode + " group is missing from the chart of accounts");
            }
        }
        return id;
    }

    // =============================================================== helpers

    private static final String ITEM_SELECT =
            "SELECT id, name, sku, kind, unit_id, category_id, hsn_sac, description, is_active, costing_method FROM item";

    private static Item mapItem(ResultSet rows) throws SQLException {
        return new Item(rows.getString(1), rows.getString(2), rows.getString(3),
                ItemKind.valueOf(rows.getString(4)), rows.getString(5), rows.getString(6),
                rows.getString(7), rows.getString(8), rows.getInt(9) == 1,
                CostingMethod.valueOf(rows.getString(10)));
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
