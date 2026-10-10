package com.accubook.trade;

import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.ReversalRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleTransactions;
import com.accubook.module.stock.StockPort;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Drafts, posts and cancels the documents of one side - sales or purchase.
 *
 * <h2>Posting is one transaction</h2>
 * Number, voucher, stock movements and the document's own status commit
 * together or not at all (P7-5). An invoice whose stock cannot be issued is not
 * posted - and leaves no gap in the number series, because the counter rolls
 * back with everything else.
 *
 * <h2>Enforcement</h2>
 * Every mutation asks for this side's module first; a document with goods also
 * asks for {@code inventory}. Writes go through {@link ModuleTransactions}, so
 * the persistence guard stands behind both checks.
 */
public final class DocumentEngine {

    private static final ModuleId INVENTORY = ModuleId.of("inventory");

    private final DocumentKind.Side side;
    private final ModuleId module;
    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final TransactionalPosting posting;
    private final Optional<StockPort> stock;
    private final Clock clock;

    private final String documents;
    private final String lines;
    private final String allocations;

    public DocumentEngine(DocumentKind.Side side, ModuleId module, ModuleTransactions transactions,
                          EntitlementGuard entitlement, TransactionalPosting posting,
                          Optional<StockPort> stock, Clock clock) {
        this.side = Objects.requireNonNull(side, "side");
        this.module = Objects.requireNonNull(module, "module");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.posting = Objects.requireNonNull(posting, "posting");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.documents = side.tablePrefix() + "_document";
        this.lines = side.tablePrefix() + "_document_line";
        this.allocations = side.tablePrefix() + "_allocation";
    }

    // ================================================================ drafts

    public Document createDraft(DraftRequest request) {
        require("create a document");
        String id = Ids.newIdString();
        write("create the document", connection -> {
            Draft draft = validate(connection, request, id);
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + documents
                    + " (id, kind, party_id, document_date, due_date, original_document_id, "
                    + (side == DocumentKind.Side.PURCHASE ? "supplier_document_no, " : "")
                    + "round_off_enabled, amount_e4, round_off_e4, total_e4, narration, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, " + (side == DocumentKind.Side.PURCHASE ? "?, " : "")
                    + "?, ?, ?, ?, ?, ?, ?)")) {
                int i = 1;
                ps.setString(i++, id);
                ps.setString(i++, request.kind().name());
                ps.setString(i++, request.partyId());
                ps.setString(i++, request.date().toString());
                ps.setString(i++, draft.dueDate == null ? null : draft.dueDate.toString());
                ps.setString(i++, request.originalDocumentId());
                if (side == DocumentKind.Side.PURCHASE) {
                    ps.setString(i++, blankToNull(request.supplierDocumentNo()));
                }
                bindTotals(ps, i, request.roundOff(), draft.money);
                ps.setString(i + 4, blankToNull(request.narration()));
                ps.setLong(i + 5, now());
                ps.setLong(i + 6, now());
                ps.executeUpdate();
            }
            insertLines(connection, id, draft.lines);
        });
        return find(id).orElseThrow();
    }

    /** Replace a draft's contents. Refused once posted - the schema refuses it too. */
    public Document updateDraft(String documentId, DraftRequest request) {
        require("change a document");
        write("change the document", connection -> {
            Header current = header(connection, documentId);
            requireDraft(current);
            if (current.kind != request.kind()) {
                throw new TradeException("A draft keeps its kind; create a new document instead");
            }
            Draft draft = validate(connection, request, documentId);
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + lines + " WHERE document_id = ?")) {
                ps.setString(1, documentId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + documents
                    + " SET party_id = ?, document_date = ?, due_date = ?, original_document_id = ?, "
                    + (side == DocumentKind.Side.PURCHASE ? "supplier_document_no = ?, " : "")
                    + "round_off_enabled = ?, amount_e4 = ?, round_off_e4 = ?, total_e4 = ?, narration = ?, "
                    + "updated_at = ? WHERE id = ?")) {
                int i = 1;
                ps.setString(i++, request.partyId());
                ps.setString(i++, request.date().toString());
                ps.setString(i++, draft.dueDate == null ? null : draft.dueDate.toString());
                ps.setString(i++, request.originalDocumentId());
                if (side == DocumentKind.Side.PURCHASE) {
                    ps.setString(i++, blankToNull(request.supplierDocumentNo()));
                }
                bindTotals(ps, i, request.roundOff(), draft.money);
                ps.setString(i + 4, blankToNull(request.narration()));
                ps.setLong(i + 5, now());
                ps.setString(i + 6, documentId);
                ps.executeUpdate();
            }
            insertLines(connection, documentId, draft.lines);
        });
        return find(documentId).orElseThrow();
    }

    /** Delete a draft. A posted document is cancelled, never deleted. */
    public void deleteDraft(String documentId) {
        require("delete a document");
        write("delete the document", connection -> {
            requireDraft(header(connection, documentId));
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + documents + " WHERE id = ?")) {
                ps.setString(1, documentId);
                ps.executeUpdate();
            }
        });
    }

    // =============================================================== posting

    /**
     * Post a draft: number it, post its voucher, move its stock - atomically.
     *
     * @throws TradeException if refused; nothing is written
     */
    public Document post(String documentId) {
        require("post a document");
        write("post the document", connection -> {
            Header doc = header(connection, documentId);
            requireDraft(doc);
            if (doc.total.signum() <= 0) {
                throw new TradeException("A document must have a total above zero to post");
            }
            Lookups.FiscalYearRow year = Lookups.fiscalYearFor(connection, doc.date);
            if (doc.kind.isNote()) {
                requireWithinUncredited(connection, doc);
                requireItemsWithinOriginal(connection, doc);
            }
            List<DocumentLine> docLines = loadLines(connection, documentId);
            boolean movesStock = docLines.stream().anyMatch(line -> line.warehouseId() != null);
            if (movesStock) {
                entitlement.requireWritable(INVENTORY, "move stock for a document");
            }

            String number = DocumentNumbers.format(doc.kind, year.start(), year.end(),
                    Lookups.nextSequence(connection, DocumentNumbers.scope(doc.kind, year.id()), now()));

            RoundOff money = new RoundOff(doc.amount, doc.total, doc.roundOff);
            String narration = label(doc.kind) + " " + number + " - " + partyName(connection, doc.partyId);
            List<EntryLine> entries = PostingPlan.entries(doc.kind, Lookups.partyLedger(connection, doc.partyId),
                    Lookups.ledgerByCode(connection, side.counterLedgerCode()), Lookups.ledgerByCode(connection, "ROUND_OFF"),
                    money, narration);
            PostedVoucher voucher = posting.postWithin(connection, new PostingRequest(module, year.id(),
                    Lookups.voucherType(connection, doc.kind.voucherTypeCode()), doc.date, number, narration, entries,
                    "doc:" + documentId));

            for (DocumentLine line : docLines) {
                if (line.warehouseId() != null) {
                    stockPort().move(connection, doc.kind.stockKind(), line.itemId(), line.warehouseId(),
                            line.quantity(), unitCost(doc.kind, line), doc.date, doc.kind.name(), documentId, number);
                }
            }

            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + documents
                    + " SET status = 'POSTED', number = ?, fiscal_year_id = ?, voucher_id = ?, updated_at = ? "
                    + "WHERE id = ?")) {
                ps.setString(1, number);
                ps.setString(2, year.id());
                ps.setString(3, voucher.voucherId());
                ps.setLong(4, now());
                ps.setString(5, documentId);
                ps.executeUpdate();
            }
            if (doc.kind.isNote()) {
                applyNoteToOriginal(connection, doc);
            }
        });
        return find(documentId).orElseThrow();
    }

    /**
     * P7-13: a posted note reduces its original document's outstanding at once,
     * up to what is still owed on it. If the original was already paid, the rest
     * stays as an unallocated credit for the party - to be applied to another
     * document or refunded.
     */
    private void applyNoteToOriginal(Connection connection, Header note) throws SQLException {
        long outstandingE4;
        try (PreparedStatement ps = connection.prepareStatement("SELECT d.total_e4 - COALESCE((SELECT SUM(amount_e4) "
                + "FROM " + allocations + " WHERE document_id = d.id AND removed_date IS NULL), 0) FROM "
                + documents + " d WHERE d.id = ?")) {
            ps.setString(1, note.originalId);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                outstandingE4 = rows.getLong(1);
            }
        }
        long apply = Math.min(outstandingE4, MoneyMapper.toStored(note.total, Scale.MONEY));
        if (apply <= 0) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + allocations
                + " (id, source_type, source_id, document_id, amount_e4, effective_date, created_at) "
                + "VALUES (?, 'NOTE', ?, ?, ?, ?, ?)")) {
            ps.setString(1, Ids.newIdString());
            ps.setString(2, note.id);
            ps.setString(3, note.originalId);
            ps.setLong(4, apply);
            ps.setString(5, note.date.toString());
            ps.setLong(6, now());
            ps.executeUpdate();
        }
    }

    /**
     * Cancel a posted document (P7-12): reverse its voucher and its stock in
     * the period of {@code cancellationDate}, keep its number.
     *
     * <p>Refused while a note references it - the note would correct a document
     * that no longer stands. Allocations against it (P7d) will be refused too.
     */
    public Document cancel(String documentId, String reason, LocalDate cancellationDate) {
        require("cancel a document");
        if (reason == null || reason.isBlank()) {
            throw new TradeException("A cancellation needs a reason; it stays on the record");
        }
        Objects.requireNonNull(cancellationDate, "cancellationDate");
        write("cancel the document", connection -> {
            Header doc = header(connection, documentId);
            if (doc.status != DocumentStatus.POSTED) {
                throw new TradeException("Only a posted document can be cancelled; this one is " + doc.status);
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT number FROM " + documents
                    + " WHERE original_document_id = ? AND status = 'POSTED' LIMIT 1")) {
                ps.setString(1, documentId);
                try (ResultSet rows = ps.executeQuery()) {
                    if (rows.next()) {
                        throw new TradeException(doc.number + " is corrected by " + rows.getString(1)
                                + ". Cancel that note first.");
                    }
                }
            }
            if (cancellationDate.isBefore(doc.date)) {
                throw new TradeException("A document cannot be cancelled before its own date");
            }
            try (PreparedStatement ps = connection.prepareStatement("SELECT EXISTS (SELECT 1 FROM " + allocations
                    + " WHERE document_id = ? AND removed_date IS NULL)")) {
                ps.setString(1, documentId);
                try (ResultSet rows = ps.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) == 1) {
                        throw new TradeException(doc.number + " has payments applied to it. Remove those "
                                + "allocations, or cancel the receipt, before cancelling it.");
                    }
                }
            }
            if (doc.kind.isNote()) {
                // The note's own automatic allocation goes with it, dated today.
                SettlementEngine.removeAllocationsOfSource(connection, allocations, Allocation.SourceType.NOTE,
                        documentId, cancellationDate, "Note cancelled: " + reason.strip());
            }
            Lookups.FiscalYearRow year = Lookups.fiscalYearFor(connection, cancellationDate);
            List<DocumentLine> docLines = loadLines(connection, documentId);
            if (docLines.stream().anyMatch(line -> line.warehouseId() != null)) {
                entitlement.requireWritable(INVENTORY, "reverse a document's stock");
            }

            PostedVoucher reversal = posting.reverseWithin(connection, doc.voucherId, new ReversalRequest(module,
                    year.id(), cancellationDate, "Cancelled " + doc.number + ": " + reason.strip(), null,
                    "doc-cancel:" + documentId));

            for (DocumentLine line : docLines) {
                if (line.warehouseId() != null) {
                    stockPort().move(connection, doc.kind.cancellationStockKind(), line.itemId(), line.warehouseId(),
                            line.quantity(), null, cancellationDate, doc.kind.name() + "_CANCEL", documentId,
                            "Cancellation of " + doc.number);
                }
            }

            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + documents
                    + " SET status = 'CANCELLED', cancellation_voucher_id = ?, cancellation_reason = ?, "
                    + "updated_at = ? WHERE id = ?")) {
                ps.setString(1, reversal.voucherId());
                ps.setString(2, reason.strip());
                ps.setLong(3, now());
                ps.setString(4, documentId);
                ps.executeUpdate();
            }
        });
        return find(documentId).orElseThrow();
    }

    // ================================================================= reads

    public Optional<Document> find(String documentId) {
        return read(connection -> {
            List<Document> found = documents(connection, " WHERE id = ?", documentId);
            return found.isEmpty() ? Optional.<Document>empty() : Optional.of(found.get(0));
        });
    }

    /** Every document on this side, newest first. */
    public List<Document> list() {
        return read(connection -> documents(connection, " ORDER BY document_date DESC, created_at DESC"));
    }

    /**
     * How much of a posted document can still be credited (P7-11): its total
     * less every posted note against it.
     */
    public BigDecimal uncredited(String documentId) {
        return read(connection -> {
            Header doc = header(connection, documentId);
            return doc.total.subtract(creditedAgainst(connection, documentId));
        });
    }

    // ============================================================ validation

    private record Draft(LocalDate dueDate, RoundOff money, List<LineValues> lines) {
    }

    private record LineValues(String itemId, String description, BigDecimal quantity, BigDecimal rate,
                              int discountBp, BigDecimal amount, String warehouseId) {
    }

    private Draft validate(Connection connection, DraftRequest request, String selfId) throws SQLException {
        if (request.kind().side() != side) {
            throw new TradeException("A " + label(request.kind()) + " does not belong on this side");
        }
        PartyRow party = party(connection, request.partyId());
        boolean roleFits = side == DocumentKind.Side.SALES ? !"VENDOR".equals(party.role)
                : !"CUSTOMER".equals(party.role);
        if (!roleFits) {
            throw new TradeException("\"" + party.name + "\" is " + (side == DocumentKind.Side.SALES
                    ? "a vendor only; mark it as both to sell to it" : "a customer only; mark it as both to buy from it"));
        }
        if (!party.active) {
            throw new TradeException("\"" + party.name + "\" is inactive");
        }
        if (side == DocumentKind.Side.SALES && blankToNull(request.supplierDocumentNo()) != null) {
            throw new TradeException("A supplier's bill number belongs on a purchase document");
        }

        if (request.kind().isNote()) {
            if (request.originalDocumentId() == null) {
                throw new TradeException("A " + label(request.kind()) + " must name the "
                        + label(request.kind().parent()) + " it corrects");
            }
            Header original = header(connection, request.originalDocumentId());
            if (original.kind != request.kind().parent() || original.status != DocumentStatus.POSTED) {
                throw new TradeException("A " + label(request.kind()) + " corrects a posted "
                        + label(request.kind().parent()));
            }
            if (!original.partyId.equals(request.partyId())) {
                throw new TradeException("A note is for the same party as the document it corrects");
            }
            if (request.date().isBefore(original.date)) {
                throw new TradeException("A note cannot be dated before " + original.number + ", which it corrects");
            }
            if (original.id.equals(selfId)) {
                throw new TradeException("A document cannot correct itself");
            }
        } else if (request.originalDocumentId() != null) {
            throw new TradeException("Only a credit or debit note refers to an original document");
        }

        if (request.lines().isEmpty()) {
            throw new TradeException("A document needs at least one line");
        }
        List<LineValues> values = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        for (LineRequest line : request.lines()) {
            LineValues value = validateLine(connection, line);
            values.add(value);
            amount = amount.add(value.amount);
        }

        LocalDate dueDate = request.dueDate();
        if (dueDate == null && !request.kind().isNote() && party.creditDays != null) {
            dueDate = request.date().plusDays(party.creditDays);
        }
        if (dueDate != null && dueDate.isBefore(request.date())) {
            throw new TradeException("A due date cannot be before the document date");
        }
        return new Draft(dueDate, RoundOff.of(amount, request.roundOff()), values);
    }

    private LineValues validateLine(Connection connection, LineRequest line) throws SQLException {
        String description = blankToNull(line.description());
        String warehouse = line.warehouseId();
        if (line.itemId() == null) {
            if (description == null) {
                throw new TradeException("A line without an item needs a description");
            }
            if (warehouse != null) {
                throw new TradeException("Only a stocked item is taken from a warehouse");
            }
        } else {
            StockPort.ItemFacts item = stockPort().item(connection, line.itemId())
                    .orElseThrow(() -> new TradeException("No such item: " + line.itemId()));
            if (!item.active()) {
                throw new TradeException("\"" + item.name() + "\" is inactive");
            }
            if (item.goods() && warehouse == null) {
                throw new TradeException("Choose the warehouse \"" + item.name() + "\" comes from or goes to");
            }
            if (!item.goods() && warehouse != null) {
                throw new TradeException("\"" + item.name() + "\" is a service and has no stock");
            }
            if (line.quantity() != null && line.quantity().stripTrailingZeros().scale() > item.unitDecimals()) {
                throw new TradeException("\"" + item.name() + "\" is counted in " + item.unitSymbol() + ", which allows "
                        + item.unitDecimals() + " decimal places; " + line.quantity().toPlainString() + " has more");
            }
            description = description == null ? item.name() : description;
        }
        if (line.quantity() != null && line.quantity().stripTrailingZeros().scale() > Scale.QUANTITY.digits()) {
            throw new TradeException("A quantity may have at most four decimal places");
        }
        BigDecimal amount = LineMath.amount(line.quantity(), line.rate(), line.discountBp());
        return new LineValues(line.itemId(), description, line.quantity(), line.rate(), line.discountBp(), amount,
                warehouse);
    }

    private void requireWithinUncredited(Connection connection, Header note) throws SQLException {
        Header original = header(connection, note.originalId);
        if (original.status != DocumentStatus.POSTED) {
            throw new TradeException(original.number + " is " + original.status + "; it can no longer be corrected");
        }
        BigDecimal remaining = original.total.subtract(creditedAgainst(connection, original.id));
        if (note.total.compareTo(remaining) > 0) {
            throw new TradeException("This " + label(note.kind) + " for " + note.total.toPlainString()
                    + " exceeds what remains of " + original.number + " (" + remaining.toPlainString() + ")");
        }
    }

    /**
     * P7-17: a note may not return more of an item than the original carried,
     * less what earlier posted notes already returned. The amount limit alone
     * would let a note return 10 kg against an invoice for 5 kg if the value
     * happened to fit. Free-text lines carry no quantity of anything and are
     * governed by the amount limit only.
     */
    private void requireItemsWithinOriginal(Connection connection, Header note) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                WITH this_note AS (
                    SELECT item_id, SUM(quantity_e4) AS q, MIN(description) AS name
                    FROM %1$s WHERE document_id = ? AND item_id IS NOT NULL GROUP BY item_id
                ),
                original AS (
                    SELECT item_id, SUM(quantity_e4) AS q FROM %1$s
                    WHERE document_id = ? AND item_id IS NOT NULL GROUP BY item_id
                ),
                earlier AS (
                    SELECT l.item_id, SUM(l.quantity_e4) AS q
                    FROM %1$s l JOIN %2$s d ON d.id = l.document_id
                    WHERE d.original_document_id = ? AND d.status = 'POSTED' AND l.item_id IS NOT NULL
                    GROUP BY l.item_id
                )
                SELECT n.name, n.q, COALESCE(o.q, 0) AS original_q, COALESCE(e.q, 0) AS earlier_q
                FROM this_note n
                LEFT JOIN original o ON o.item_id = n.item_id
                LEFT JOIN earlier e ON e.item_id = n.item_id
                WHERE n.q + COALESCE(e.q, 0) > COALESCE(o.q, 0)
                LIMIT 1
                """.formatted(lines, documents))) {
            ps.setString(1, note.id);
            ps.setString(2, note.originalId);
            ps.setString(3, note.originalId);
            try (ResultSet rows = ps.executeQuery()) {
                if (rows.next()) {
                    BigDecimal original = MoneyMapper.fromStored(rows.getLong(3), Scale.QUANTITY);
                    BigDecimal earlier = MoneyMapper.fromStored(rows.getLong(4), Scale.QUANTITY);
                    throw new TradeException("This " + label(note.kind) + " returns "
                            + plain(MoneyMapper.fromStored(rows.getLong(2), Scale.QUANTITY)) + " of \""
                            + rows.getString(1) + "\", but the original carried " + plain(original)
                            + (earlier.signum() > 0 ? " and earlier notes already returned " + plain(earlier) : "")
                            + ".");
                }
            }
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private BigDecimal creditedAgainst(Connection connection, String originalId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(total_e4), 0) FROM "
                + documents + " WHERE original_document_id = ? AND status = 'POSTED'")) {
            ps.setString(1, originalId);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                return MoneyMapper.fromStored(rows.getLong(1), Scale.MONEY);
            }
        }
    }

    // =============================================================== helpers

    private StockPort stockPort() {
        return stock.orElseThrow(() -> new TradeException(
                "This build has no inventory module, so a document line cannot name an item. "
                        + "Use a free-text line instead."));
    }

    /** Purchase side records what a unit cost, for valuation in P8; sales records none. */
    private static BigDecimal unitCost(DocumentKind kind, DocumentLine line) {
        if (kind.side() != DocumentKind.Side.PURCHASE || !kind.stockKind().bringsStockIn()) {
            return null;
        }
        return line.amount().divide(line.quantity(), Scale.MONEY.digits(), RoundingMode.HALF_UP);
    }

    private static String label(DocumentKind kind) {
        return switch (kind) {
            case SALES_INVOICE -> "sales invoice";
            case CREDIT_NOTE -> "credit note";
            case PURCHASE_BILL -> "purchase bill";
            case DEBIT_NOTE -> "debit note";
        };
    }

    private void require(String operation) {
        entitlement.requireWritable(module, operation);
    }

    private long now() {
        return clock.millis();
    }

    private static void requireDraft(Header doc) {
        if (doc.status != DocumentStatus.DRAFT) {
            throw new TradeException(doc.number + " is " + doc.status.name().toLowerCase(java.util.Locale.ROOT)
                    + " and cannot be changed" + (doc.status == DocumentStatus.POSTED ? "; cancel it instead" : ""));
        }
    }

    private static void bindTotals(PreparedStatement ps, int start, boolean roundOff, RoundOff money)
            throws SQLException {
        ps.setInt(start, roundOff ? 1 : 0);
        ps.setLong(start + 1, MoneyMapper.toStored(money.amount(), Scale.MONEY));
        ps.setLong(start + 2, MoneyMapper.toStored(money.roundOff(), Scale.MONEY));
        ps.setLong(start + 3, MoneyMapper.toStored(money.total(), Scale.MONEY));
    }

    private void insertLines(Connection connection, String documentId, List<LineValues> values) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + lines
                + " (id, document_id, sequence, item_id, description, quantity_e4, rate_e4, discount_bp, "
                + "amount_e4, warehouse_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int sequence = 1;
            for (LineValues line : values) {
                ps.setString(1, Ids.newIdString());
                ps.setString(2, documentId);
                ps.setInt(3, sequence++);
                ps.setString(4, line.itemId);
                ps.setString(5, line.description);
                ps.setLong(6, MoneyMapper.toStored(line.quantity.stripTrailingZeros(), Scale.QUANTITY));
                ps.setLong(7, MoneyMapper.toStored(line.rate.stripTrailingZeros(), Scale.MONEY));
                ps.setInt(8, line.discountBp);
                ps.setLong(9, MoneyMapper.toStored(line.amount, Scale.MONEY));
                ps.setString(10, line.warehouseId);
                ps.executeUpdate();
            }
        }
    }

    private record Header(String id, DocumentKind kind, String number, String partyId, LocalDate date,
                          DocumentStatus status, String originalId, BigDecimal amount, BigDecimal roundOff,
                          BigDecimal total, String voucherId) {
    }

    private Header header(Connection connection, String documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, kind, number, party_id, document_date, "
                + "status, original_document_id, amount_e4, round_off_e4, total_e4, voucher_id FROM " + documents
                + " WHERE id = ?")) {
            ps.setString(1, documentId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No such document: " + documentId);
                }
                return new Header(rows.getString(1), DocumentKind.valueOf(rows.getString(2)), rows.getString(3),
                        rows.getString(4), LocalDate.parse(rows.getString(5)),
                        DocumentStatus.valueOf(rows.getString(6)), rows.getString(7),
                        MoneyMapper.fromStored(rows.getLong(8), Scale.MONEY),
                        MoneyMapper.fromStored(rows.getLong(9), Scale.MONEY),
                        MoneyMapper.fromStored(rows.getLong(10), Scale.MONEY), rows.getString(11));
            }
        }
    }

    private List<DocumentLine> loadLines(Connection connection, String documentId) throws SQLException {
        List<DocumentLine> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, sequence, item_id, description, "
                + "quantity_e4, rate_e4, discount_bp, amount_e4, warehouse_id FROM " + lines
                + " WHERE document_id = ? ORDER BY sequence")) {
            ps.setString(1, documentId);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    result.add(new DocumentLine(rows.getString(1), rows.getInt(2), rows.getString(3),
                            rows.getString(4), MoneyMapper.fromStored(rows.getLong(5), Scale.QUANTITY),
                            MoneyMapper.fromStored(rows.getLong(6), Scale.MONEY), rows.getInt(7),
                            MoneyMapper.fromStored(rows.getLong(8), Scale.MONEY), rows.getString(9)));
                }
            }
        }
        return result;
    }

    private List<Document> documents(Connection connection, String where, String... parameters) throws SQLException {
        List<Document> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, kind, number, party_id, document_date, "
                + "due_date, status, original_document_id, amount_e4, round_off_e4, total_e4, voucher_id, "
                + "cancellation_voucher_id, "
                + (side == DocumentKind.Side.PURCHASE ? "supplier_document_no" : "NULL") + ", narration FROM "
                + documents + where)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    String due = rows.getString(6);
                    result.add(new Document(id, DocumentKind.valueOf(rows.getString(2)), rows.getString(3),
                            rows.getString(4), LocalDate.parse(rows.getString(5)),
                            due == null ? null : LocalDate.parse(due), DocumentStatus.valueOf(rows.getString(7)),
                            rows.getString(8), MoneyMapper.fromStored(rows.getLong(9), Scale.MONEY),
                            MoneyMapper.fromStored(rows.getLong(10), Scale.MONEY),
                            MoneyMapper.fromStored(rows.getLong(11), Scale.MONEY), rows.getString(12),
                            rows.getString(13), rows.getString(14), rows.getString(15), List.of()));
                }
            }
        }
        List<Document> withLines = new ArrayList<>(result.size());
        for (Document d : result) {
            withLines.add(new Document(d.id(), d.kind(), d.number(), d.partyId(), d.date(), d.dueDate(), d.status(),
                    d.originalDocumentId(), d.amount(), d.roundOff(), d.total(), d.voucherId(),
                    d.cancellationVoucherId(), d.supplierDocumentNo(), d.narration(), loadLines(connection, d.id())));
        }
        return withLines;
    }

    private record PartyRow(String name, String role, boolean active, Integer creditDays) {
    }

    private static PartyRow party(Connection connection, String partyId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT l.name, p.role, l.is_active, p.credit_days "
                + "FROM party p JOIN ledger l ON l.id = p.ledger_id WHERE p.id = ?")) {
            ps.setString(1, partyId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No such party: " + partyId);
                }
                int days = rows.getInt(4);
                return new PartyRow(rows.getString(1), rows.getString(2), rows.getInt(3) == 1,
                        rows.wasNull() ? null : days);
            }
        }
    }

    private static String partyName(Connection connection, String partyId) throws SQLException {
        return party(connection, partyId).name;
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
            throw new TradeException("Could not read documents: " + e.getMessage(), e);
        }
    }
}
