package com.accubook.trade;

import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleTransactions;
import com.accubook.module.stock.StockPort;
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
 * Quotations (sales side) and orders (both sides).
 *
 * <p>Commitments only: nothing here posts or moves stock. An order is fulfilled
 * by the invoices or bills made from it (P7-18); what is fulfilled is read back
 * from their posted, linked lines - there is no counter to forget to update
 * (R-14). Numbers come from the counter table inside the transaction (D-11),
 * given when a quotation is sent or an order confirmed.
 */
public final class OrderEngine {

    private final DocumentKind.Side side;
    private final ModuleId module;
    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final DocumentEngine documents;
    private final Optional<StockPort> stock;
    private final Clock clock;

    private final String orders;
    private final String orderLines;
    private final String documentLines;
    private final String documentTable;

    public OrderEngine(DocumentKind.Side side, ModuleId module, ModuleTransactions transactions,
                       EntitlementGuard entitlement, DocumentEngine documents, Optional<StockPort> stock,
                       Clock clock) {
        this.side = Objects.requireNonNull(side, "side");
        this.module = Objects.requireNonNull(module, "module");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.orders = side.tablePrefix() + "_order";
        this.orderLines = side.tablePrefix() + "_order_line";
        this.documentLines = side.tablePrefix() + "_document_line";
        this.documentTable = side.tablePrefix() + "_document";
    }

    // ============================================================ quotations

    public Quotation createQuotation(OrderRequest request) {
        requireSales();
        require("create a quotation");
        String id = Ids.newIdString();
        write("create the quotation", connection -> {
            Contents contents = validate(connection, request, true);
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO sales_quotation (id, party_id, "
                    + "quotation_date, valid_until, amount_e4, narration, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, request.partyId());
                ps.setString(3, request.date().toString());
                ps.setString(4, request.endDate().toString());
                ps.setLong(5, money(contents.amount));
                ps.setString(6, blankToNull(request.narration()));
                ps.setLong(7, now());
                ps.setLong(8, now());
                ps.executeUpdate();
            }
            insertLines(connection, "sales_quotation_line", "quotation_id", id, contents.lines);
        });
        return findQuotation(id, request.date()).orElseThrow();
    }

    public void deleteQuotation(String quotationId) {
        requireSales();
        require("delete a quotation");
        write("delete the quotation", connection -> {
            if (!"DRAFT".equals(quotationStatus(connection, quotationId))) {
                throw new TradeException("Only a draft quotation can be deleted");
            }
            exec(connection, "DELETE FROM sales_quotation WHERE id = ?", quotationId);
        });
    }

    /** Number and send a draft quotation. */
    public Quotation sendQuotation(String quotationId) {
        requireSales();
        require("send a quotation");
        write("send the quotation", connection -> {
            if (!"DRAFT".equals(quotationStatus(connection, quotationId))) {
                throw new TradeException("Only a draft quotation can be sent");
            }
            LocalDate date = LocalDate.parse(Lookups.single(connection,
                    "SELECT quotation_date FROM sales_quotation WHERE id = ?", quotationId, "No such quotation"));
            String number = nextNumber(connection, "QT", date);
            try (PreparedStatement ps = connection.prepareStatement("UPDATE sales_quotation SET status = 'SENT', "
                    + "number = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, number);
                ps.setLong(2, now());
                ps.setString(3, quotationId);
                ps.executeUpdate();
            }
        });
        return findQuotation(quotationId, LocalDate.now(clock)).orElseThrow();
    }

    /** Record the customer's acceptance. Refused once expired on {@code on}. */
    public void acceptQuotation(String quotationId, LocalDate on) {
        decide(quotationId, on, "ACCEPTED");
    }

    public void rejectQuotation(String quotationId, LocalDate on) {
        decide(quotationId, on, "REJECTED");
    }

    private void decide(String quotationId, LocalDate on, String decision) {
        requireSales();
        require("decide a quotation");
        write("record the decision", connection -> {
            Quotation q = quotationAsOf(connection, quotationId, on);
            if (q.status() != QuotationStatus.SENT) {
                throw new TradeException("Quotation " + q.number() + " is " + label(q.status())
                        + "; only a sent quotation can be accepted or rejected");
            }
            exec(connection, "UPDATE sales_quotation SET status = '" + decision + "' WHERE id = ?", quotationId);
        });
    }

    /** P7-19: copy a quotation into a draft sales order, and mark it converted. */
    public Order convertQuotationToOrder(String quotationId, LocalDate orderDate) {
        requireSales();
        require("convert a quotation");
        String orderId = Ids.newIdString();
        write("convert the quotation", connection -> {
            Quotation q = requireConvertible(connection, quotationId, orderDate);
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO sales_order (id, party_id, "
                    + "order_date, quotation_id, amount_e4, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, orderId);
                ps.setString(2, q.partyId());
                ps.setString(3, orderDate.toString());
                ps.setString(4, quotationId);
                ps.setLong(5, money(q.amount()));
                ps.setLong(6, now());
                ps.setLong(7, now());
                ps.executeUpdate();
            }
            insertLines(connection, orderLines, "order_id", orderId, q.lines().stream()
                    .map(l -> new LineValues(l.itemId(), l.description(), l.quantity(), l.rate(), l.discountBp(),
                            l.amount(), l.warehouseId())).toList());
            exec(connection, "UPDATE sales_quotation SET status = 'CONVERTED' WHERE id = ?", quotationId);
        });
        return findOrder(orderId).orElseThrow();
    }

    /**
     * P7-19: copy a quotation straight into a draft sales invoice, and mark it
     * converted. {@code defaultWarehouseId} is used for goods lines that name none.
     */
    public Document convertQuotationToInvoice(String quotationId, LocalDate invoiceDate, String defaultWarehouseId) {
        requireSales();
        require("convert a quotation");
        Quotation q = read(connection -> requireConvertible(connection, quotationId, invoiceDate));
        List<LineRequest> lines = new ArrayList<>();
        for (OrderLine line : q.lines()) {
            lines.add(new LineRequest(line.itemId(), line.description(), line.quantity(), line.rate(),
                    line.discountBp(), warehouseFor(line.itemId(), line.warehouseId(), defaultWarehouseId)));
        }
        // The document engine marks the quotation converted in the same
        // transaction as the draft.
        return documents.createDraft(DraftRequest.of(DocumentKind.SALES_INVOICE, q.partyId(), invoiceDate,
                lines).withQuotation(quotationId));
    }

    public Optional<Quotation> findQuotation(String quotationId, LocalDate asOf) {
        requireSales();
        return read(connection -> {
            try {
                return Optional.of(quotationAsOf(connection, quotationId, asOf));
            } catch (TradeException missing) {
                return Optional.<Quotation>empty();
            }
        });
    }

    // ================================================================ orders

    public Order createOrder(OrderRequest request) {
        require("create an order");
        String id = Ids.newIdString();
        write("create the order", connection -> {
            Contents contents = validate(connection, request, false);
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + orders + " (id, party_id, "
                    + "order_date, expected_date, amount_e4, narration, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, request.partyId());
                ps.setString(3, request.date().toString());
                ps.setString(4, request.endDate() == null ? null : request.endDate().toString());
                ps.setLong(5, money(contents.amount));
                ps.setString(6, blankToNull(request.narration()));
                ps.setLong(7, now());
                ps.setLong(8, now());
                ps.executeUpdate();
            }
            insertLines(connection, orderLines, "order_id", id, contents.lines);
        });
        return findOrder(id).orElseThrow();
    }

    public void deleteOrder(String orderId) {
        require("delete an order");
        write("delete the order", connection -> {
            if (!"DRAFT".equals(orderStatus(connection, orderId))) {
                throw new TradeException("Only a draft order can be deleted; close or cancel a confirmed one");
            }
            exec(connection, "DELETE FROM " + orders + " WHERE id = ?", orderId);
        });
    }

    /** Number a draft order and open it for fulfilment. */
    public Order confirmOrder(String orderId) {
        require("confirm an order");
        write("confirm the order", connection -> {
            if (!"DRAFT".equals(orderStatus(connection, orderId))) {
                throw new TradeException("Only a draft order can be confirmed");
            }
            LocalDate date = LocalDate.parse(Lookups.single(connection,
                    "SELECT order_date FROM " + orders + " WHERE id = ?", orderId, "No such order"));
            String number = nextNumber(connection, side == DocumentKind.Side.SALES ? "SO" : "PO", date);
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + orders
                    + " SET status = 'CONFIRMED', number = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, number);
                ps.setLong(2, now());
                ps.setString(3, orderId);
                ps.executeUpdate();
            }
        });
        return findOrder(orderId).orElseThrow();
    }

    /** P7-20: short-close - whatever is still open will not be fulfilled. */
    public Order closeOrder(String orderId, String reason) {
        return finish(orderId, reason, "CLOSED");
    }

    /** Cancel an order nothing has been fulfilled against. */
    public Order cancelOrder(String orderId, String reason) {
        return finish(orderId, reason, "CANCELLED");
    }

    private Order finish(String orderId, String reason, String status) {
        require("close an order");
        if (reason == null || reason.isBlank()) {
            throw new TradeException("Closing or cancelling an order needs a reason");
        }
        write("close the order", connection -> {
            if (!"CONFIRMED".equals(orderStatus(connection, orderId))) {
                throw new TradeException("Only a confirmed order can be closed or cancelled");
            }
            if ("CANCELLED".equals(status) && fulfilledAnything(connection, orderId)) {
                throw new TradeException("Part of this order has been fulfilled; short-close it instead");
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + orders
                    + " SET status = ?, close_reason = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setString(2, reason.strip());
                ps.setLong(3, now());
                ps.setString(4, orderId);
                ps.executeUpdate();
            }
        });
        return findOrder(orderId).orElseThrow();
    }

    /**
     * P7-18: a draft invoice or bill for everything still open on the order,
     * each line linked to the order line it fulfils. Posting it fulfils them.
     */
    public Document convertOrder(String orderId, LocalDate documentDate, String defaultWarehouseId) {
        require("convert an order");
        Order order = findOrder(orderId).orElseThrow(() -> new TradeException("No such order: " + orderId));
        if (order.status() != OrderStatus.OPEN && order.status() != OrderStatus.PARTIAL) {
            throw new TradeException("Order " + order.number() + " is " + label(order.status())
                    + "; there is nothing to fulfil");
        }
        List<LineRequest> lines = new ArrayList<>();
        for (OrderLine line : order.lines()) {
            if (line.open().signum() > 0) {
                lines.add(new LineRequest(line.itemId(), line.description(), line.open(), line.rate(),
                        line.discountBp(), warehouseFor(line.itemId(), line.warehouseId(), defaultWarehouseId),
                        line.id()));
            }
        }
        DocumentKind kind = side == DocumentKind.Side.SALES ? DocumentKind.SALES_INVOICE : DocumentKind.PURCHASE_BILL;
        DraftRequest draft = DraftRequest.of(kind, order.partyId(), documentDate, lines);
        return documents.createDraft(order.quotationId() == null ? draft : draft.withQuotation(order.quotationId()));
    }

    public Optional<Order> findOrder(String orderId) {
        return read(connection -> {
            List<Order> found = ordersWhere(connection, " WHERE id = ?", orderId);
            return found.isEmpty() ? Optional.<Order>empty() : Optional.of(found.get(0));
        });
    }

    public List<Order> listOrders() {
        return read(connection -> ordersWhere(connection, " ORDER BY order_date DESC, created_at DESC"));
    }

    // ============================================================ validation

    private record LineValues(String itemId, String description, BigDecimal quantity, BigDecimal rate,
                              int discountBp, BigDecimal amount, String warehouseId) {
    }

    private record Contents(BigDecimal amount, List<LineValues> lines) {
    }

    private Contents validate(Connection connection, OrderRequest request, boolean quotation) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT l.name, p.role, l.is_active FROM party p "
                + "JOIN ledger l ON l.id = p.ledger_id WHERE p.id = ?")) {
            ps.setString(1, request.partyId());
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No such party: " + request.partyId());
                }
                String role = rows.getString(2);
                if (side == DocumentKind.Side.SALES ? "VENDOR".equals(role) : "CUSTOMER".equals(role)) {
                    throw new TradeException("\"" + rows.getString(1) + "\" is "
                            + (side == DocumentKind.Side.SALES ? "a vendor only" : "a customer only"));
                }
                if (rows.getInt(3) == 0) {
                    throw new TradeException("\"" + rows.getString(1) + "\" is inactive");
                }
            }
        }
        if (quotation && request.endDate() == null) {
            throw new TradeException("A quotation needs a valid-until date");
        }
        if (request.endDate() != null && request.endDate().isBefore(request.date())) {
            throw new TradeException(quotation ? "A quotation cannot expire before its date"
                    : "An order cannot be expected before it is placed");
        }
        if (request.lines().isEmpty()) {
            throw new TradeException("At least one line is needed");
        }
        StockPort port = stock.orElseThrow(() -> new TradeException(
                "Orders and quotations name stocked items, so they need the inventory module"));
        List<LineValues> values = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        for (OrderLineRequest line : request.lines()) {
            StockPort.ItemFacts item = port.item(connection, line.itemId())
                    .orElseThrow(() -> new TradeException("No such item: " + line.itemId()));
            if (!item.active()) {
                throw new TradeException("\"" + item.name() + "\" is inactive");
            }
            if (line.quantity() != null && line.quantity().stripTrailingZeros().scale() > item.unitDecimals()) {
                throw new TradeException("\"" + item.name() + "\" is counted in " + item.unitSymbol()
                        + ", which allows " + item.unitDecimals() + " decimal places");
            }
            if (!item.goods() && line.warehouseId() != null) {
                throw new TradeException("\"" + item.name() + "\" is a service and has no stock");
            }
            BigDecimal lineAmount = LineMath.amount(line.quantity(), line.rate(), line.discountBp());
            String description = blankToNull(line.description()) == null ? item.name() : line.description().strip();
            values.add(new LineValues(line.itemId(), description, line.quantity(), line.rate(), line.discountBp(),
                    lineAmount, line.warehouseId()));
            amount = amount.add(lineAmount);
        }
        return new Contents(amount, values);
    }

    private Quotation requireConvertible(Connection connection, String quotationId, LocalDate on) throws SQLException {
        Quotation q = quotationAsOf(connection, quotationId, on);
        if (q.status() != QuotationStatus.SENT && q.status() != QuotationStatus.ACCEPTED) {
            throw new TradeException("Quotation " + (q.number() == null ? "(draft)" : q.number()) + " is "
                    + label(q.status()) + " and cannot be converted");
        }
        return q;
    }

    /** Goods need a warehouse to move from or into; services must have none. */
    private String warehouseFor(String itemId, String proposed, String fallback) {
        boolean goods = read(connection -> stock.orElseThrow().item(connection, itemId)
                .map(StockPort.ItemFacts::goods).orElse(false));
        if (!goods) {
            return null;
        }
        String warehouse = proposed != null ? proposed : fallback;
        if (warehouse == null) {
            throw new TradeException("Choose a warehouse for the goods on this document");
        }
        return warehouse;
    }

    // =============================================================== helpers

    private Quotation quotationAsOf(Connection connection, String quotationId, LocalDate asOf) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT number, party_id, quotation_date, valid_until, "
                + "status, amount_e4 FROM sales_quotation WHERE id = ?")) {
            ps.setString(1, quotationId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No such quotation: " + quotationId);
                }
                LocalDate validUntil = LocalDate.parse(rows.getString(4));
                QuotationStatus stored = QuotationStatus.valueOf(rows.getString(5));
                boolean undecided = stored == QuotationStatus.SENT || stored == QuotationStatus.ACCEPTED;
                QuotationStatus status = undecided && asOf.isAfter(validUntil) ? QuotationStatus.EXPIRED : stored;
                return new Quotation(quotationId, rows.getString(1), rows.getString(2),
                        LocalDate.parse(rows.getString(3)), validUntil, status,
                        MoneyMapper.fromStored(rows.getLong(6), Scale.MONEY),
                        loadLines(connection, "sales_quotation_line", "quotation_id", quotationId, false));
            }
        }
    }

    private List<Order> ordersWhere(Connection connection, String where, String... parameters) throws SQLException {
        List<Order> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, number, party_id, order_date, "
                + "expected_date, " + (side == DocumentKind.Side.SALES ? "quotation_id" : "NULL")
                + ", status, close_reason, amount_e4 FROM " + orders + where)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    List<OrderLine> lines = loadLines(connection, orderLines, "order_id", id, true);
                    String expected = rows.getString(5);
                    result.add(new Order(id, rows.getString(2), rows.getString(3), LocalDate.parse(rows.getString(4)),
                            expected == null ? null : LocalDate.parse(expected), rows.getString(6),
                            derive(rows.getString(7), lines), rows.getString(8),
                            MoneyMapper.fromStored(rows.getLong(9), Scale.MONEY), lines));
                }
            }
        }
        return result;
    }

    private static OrderStatus derive(String stored, List<OrderLine> lines) {
        if (!"CONFIRMED".equals(stored)) {
            return OrderStatus.valueOf(stored);
        }
        boolean anyFulfilled = lines.stream().anyMatch(l -> l.fulfilled().signum() > 0);
        boolean allFulfilled = lines.stream().allMatch(l -> l.open().signum() == 0);
        return allFulfilled ? OrderStatus.FULFILLED : anyFulfilled ? OrderStatus.PARTIAL : OrderStatus.OPEN;
    }

    private List<OrderLine> loadLines(Connection connection, String table, String parentColumn, String parentId,
                                      boolean withFulfilment) throws SQLException {
        List<OrderLine> result = new ArrayList<>();
        String fulfilled = withFulfilment
                ? "(SELECT COALESCE(SUM(dl.quantity_e4), 0) FROM " + documentLines + " dl JOIN " + documentTable
                        + " d ON d.id = dl.document_id WHERE dl.order_line_id = l.id AND d.status = 'POSTED')"
                : "0";
        try (PreparedStatement ps = connection.prepareStatement("SELECT l.id, l.sequence, l.item_id, l.description, "
                + "l.quantity_e4, l.rate_e4, l.discount_bp, l.amount_e4, l.warehouse_id, " + fulfilled + " FROM "
                + table + " l WHERE l." + parentColumn + " = ? ORDER BY l.sequence")) {
            ps.setString(1, parentId);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    result.add(new OrderLine(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4),
                            MoneyMapper.fromStored(rows.getLong(5), Scale.QUANTITY),
                            MoneyMapper.fromStored(rows.getLong(6), Scale.MONEY), rows.getInt(7),
                            MoneyMapper.fromStored(rows.getLong(8), Scale.MONEY), rows.getString(9),
                            MoneyMapper.fromStored(rows.getLong(10), Scale.QUANTITY)));
                }
            }
        }
        return result;
    }

    private void insertLines(Connection connection, String table, String parentColumn, String parentId,
                             List<LineValues> values) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + table + " (id, " + parentColumn
                + ", sequence, item_id, description, quantity_e4, rate_e4, discount_bp, amount_e4, warehouse_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int sequence = 1;
            for (LineValues line : values) {
                ps.setString(1, Ids.newIdString());
                ps.setString(2, parentId);
                ps.setInt(3, sequence++);
                ps.setString(4, line.itemId);
                ps.setString(5, line.description);
                ps.setLong(6, MoneyMapper.toStored(line.quantity.stripTrailingZeros(), Scale.QUANTITY));
                ps.setLong(7, MoneyMapper.toStored(line.rate.stripTrailingZeros(), Scale.MONEY));
                ps.setInt(8, line.discountBp);
                ps.setLong(9, money(line.amount));
                ps.setString(10, line.warehouseId);
                ps.executeUpdate();
            }
        }
    }

    private boolean fulfilledAnything(Connection connection, String orderId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM " + documentLines
                + " dl JOIN " + documentTable + " d ON d.id = dl.document_id JOIN " + orderLines
                + " ol ON ol.id = dl.order_line_id WHERE ol.order_id = ? AND d.status = 'POSTED')")) {
            ps.setString(1, orderId);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    private String nextNumber(Connection connection, String series, LocalDate date) throws SQLException {
        Lookups.FiscalYearRow year = Lookups.fiscalYearFor(connection, date);
        return DocumentNumbers.format(series, year.start(), year.end(),
                Lookups.nextSequence(connection, DocumentNumbers.scope(series, year.id()), now()));
    }

    private String orderStatus(Connection connection, String orderId) throws SQLException {
        return Lookups.single(connection, "SELECT status FROM " + orders + " WHERE id = ?", orderId,
                "No such order: " + orderId);
    }

    private static String quotationStatus(Connection connection, String quotationId) throws SQLException {
        return Lookups.single(connection, "SELECT status FROM sales_quotation WHERE id = ?", quotationId,
                "No such quotation: " + quotationId);
    }

    private static void exec(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, parameter);
            ps.executeUpdate();
        }
    }

    private void requireSales() {
        if (side != DocumentKind.Side.SALES) {
            throw new TradeException("Quotations are a sales document");
        }
    }

    private void require(String operation) {
        entitlement.requireWritable(module, operation);
    }

    private long now() {
        return clock.millis();
    }

    private static long money(BigDecimal amount) {
        return MoneyMapper.toStored(amount.stripTrailingZeros(), Scale.MONEY);
    }

    private static String label(Enum<?> status) {
        return status.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    @FunctionalInterface
    private interface Write {
        void run(Connection connection) throws SQLException;
    }

    private void write(String description, Write work) {
        try {
            transactions.inTransaction(connection -> {
                work.run(connection);
                return null;
            });
        } catch (SQLException e) {
            throw new TradeException("Could not " + description + ": " + e.getMessage(), e);
        }
    }

    private <T> T read(ModuleTransactions.Work<T> work) {
        try {
            return transactions.read(work);
        } catch (SQLException e) {
            throw new TradeException("Could not read orders: " + e.getMessage(), e);
        }
    }
}
