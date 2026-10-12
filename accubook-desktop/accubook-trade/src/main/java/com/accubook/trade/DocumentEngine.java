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
import com.accubook.tax.GstCalculator;
import com.accubook.tax.GstComponent;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.Gstin;
import com.accubook.tax.Pan;
import com.accubook.tax.LineTax;
import com.accubook.tax.PlaceOfSupply;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePackException;
import com.accubook.tax.StateCode;
import com.accubook.tax.SupplyType;
import com.accubook.tax.TaxCategory;
import com.accubook.tax.Withholding;
import com.accubook.tax.WithholdingSection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Drafts, posts and cancels the documents of one side - sales or purchase.
 *
 * <h2>Posting is one transaction</h2>
 * Number, voucher, stock movements and the document's own status commit
 * together or not at all (P7-5). An invoice whose stock cannot be issued is not
 * posted - and leaves no gap in the number series, because the counter rolls
 * back with everything else.
 *
 * <h2>GST (P9b)</h2>
 * A draft records the basis it is taxed on - the company's registration on its
 * date, the place of supply, the party's GSTIN, the rule pack - and each line's
 * category, rates and tax components. Posting refuses a draft whose basis has
 * changed since it was saved, so a posted document's tax is the tax its draft
 * showed.
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
    private final Supplier<RulePack> rules;
    private final Clock clock;

    private final String documents;
    private final String lines;
    private final String taxLines;
    private final String allocations;
    private final String orderLines;
    private final String orders;

    public DocumentEngine(DocumentKind.Side side, ModuleId module, ModuleTransactions transactions,
                          EntitlementGuard entitlement, TransactionalPosting posting,
                          Optional<StockPort> stock, Supplier<RulePack> rules, Clock clock) {
        this.side = Objects.requireNonNull(side, "side");
        this.module = Objects.requireNonNull(module, "module");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.posting = Objects.requireNonNull(posting, "posting");
        this.stock = Objects.requireNonNull(stock, "stock");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.documents = side.tablePrefix() + "_document";
        this.lines = side.tablePrefix() + "_document_line";
        this.taxLines = side.tablePrefix() + "_tax_line";
        this.allocations = side.tablePrefix() + "_allocation";
        this.orders = side.tablePrefix() + "_order";
        this.orderLines = side.tablePrefix() + "_order_line";
    }

    // ================================================================ drafts

    public Document createDraft(DraftRequest request) {
        require("create a document");
        String id = Ids.newIdString();
        write("create the document", connection -> {
            Draft draft = validate(connection, request, id);
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + documents
                    + " (id, kind, party_id, document_date, due_date, original_document_id, "
                    + (side == DocumentKind.Side.PURCHASE ? "supplier_document_no, " : "quotation_id, ")
                    + "round_off_enabled, amount_e4, round_off_e4, total_e4, narration, created_at, updated_at, "
                    + "taxable_e4, tax_e4, place_of_supply, supplier_state, supply_type, gst_scheme, party_gstin, "
                    + "reverse_charge, rule_pack_version, withholding_section) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int i = 1;
                ps.setString(i++, id);
                ps.setString(i++, request.kind().name());
                ps.setString(i++, request.partyId());
                ps.setString(i++, request.date().toString());
                ps.setString(i++, draft.dueDate == null ? null : draft.dueDate.toString());
                ps.setString(i++, request.originalDocumentId());
                ps.setString(i++, side == DocumentKind.Side.PURCHASE
                        ? blankToNull(request.supplierDocumentNo()) : request.quotationId());
                bindTotals(ps, i, request.roundOff(), draft.money);
                ps.setString(i + 4, blankToNull(request.narration()));
                ps.setLong(i + 5, now());
                ps.setLong(i + 6, now());
                bindGst(ps, i + 7, draft);
                ps.setString(i + 16, draft.withholdingSection());
                ps.executeUpdate();
            }
            insertLines(connection, id, draft.lines);
            if (request.quotationId() != null) {
                convertQuotation(connection, request.quotationId(), request.partyId(), request.date());
            }
        });
        return find(id).orElseThrow();
    }

    /**
     * P7-19: an invoice made from a quotation marks it converted in the same
     * transaction, so a draft can never exist for a quotation still shown as
     * open. Only a sent or accepted quotation, not expired on the invoice date,
     * of the same party.
     */
    private static void convertQuotation(Connection connection, String quotationId, String partyId, LocalDate on)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status, valid_until, party_id, number FROM sales_quotation WHERE id = ?")) {
            ps.setString(1, quotationId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No such quotation: " + quotationId);
                }
                String status = rows.getString(1);
                if (!"SENT".equals(status) && !"ACCEPTED".equals(status)) {
                    throw new TradeException("Quotation " + rows.getString(4) + " is "
                            + status.toLowerCase(java.util.Locale.ROOT) + " and cannot be converted");
                }
                if (on.isAfter(LocalDate.parse(rows.getString(2)))) {
                    throw new TradeException("Quotation " + rows.getString(4) + " expired on " + rows.getString(2));
                }
                if (!partyId.equals(rows.getString(3))) {
                    throw new TradeException("An invoice from a quotation is for the quotation's party");
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE sales_quotation SET status = 'CONVERTED' WHERE id = ?")) {
            ps.setString(1, quotationId);
            ps.executeUpdate();
        }
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
            if (side == DocumentKind.Side.SALES && !java.util.Objects.equals(request.quotationId(),
                    Lookups.single(connection, "SELECT COALESCE(quotation_id, '') FROM " + documents + " WHERE id = ?",
                            documentId, "No such document").transform(q -> q.isEmpty() ? null : q))) {
                throw new TradeException("A draft keeps the quotation it was made from");
            }
            Draft draft = validate(connection, request, documentId);
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + taxLines + " WHERE line_id IN "
                    + "(SELECT id FROM " + lines + " WHERE document_id = ?)")) {
                ps.setString(1, documentId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM " + lines + " WHERE document_id = ?")) {
                ps.setString(1, documentId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + documents
                    + " SET party_id = ?, document_date = ?, due_date = ?, original_document_id = ?, "
                    + (side == DocumentKind.Side.PURCHASE ? "supplier_document_no = ?, " : "quotation_id = ?, ")
                    + "round_off_enabled = ?, amount_e4 = ?, round_off_e4 = ?, total_e4 = ?, "
                    + "narration = ?, updated_at = ?, taxable_e4 = ?, tax_e4 = ?, place_of_supply = ?, "
                    + "supplier_state = ?, supply_type = ?, gst_scheme = ?, party_gstin = ?, reverse_charge = ?, "
                    + "rule_pack_version = ?, withholding_section = ? WHERE id = ?")) {
                int i = 1;
                ps.setString(i++, request.partyId());
                ps.setString(i++, request.date().toString());
                ps.setString(i++, draft.dueDate == null ? null : draft.dueDate.toString());
                ps.setString(i++, request.originalDocumentId());
                ps.setString(i++, side == DocumentKind.Side.PURCHASE
                        ? blankToNull(request.supplierDocumentNo()) : request.quotationId());
                bindTotals(ps, i, request.roundOff(), draft.money);
                ps.setString(i + 4, blankToNull(request.narration()));
                ps.setLong(i + 5, now());
                bindGst(ps, i + 6, draft);
                ps.setString(i + 15, draft.withholdingSection());
                ps.setString(i + 16, documentId);
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
            GstBasis basis = requireBasisCurrent(connection, doc);
            requireWithinCompositionLimit(connection, doc, basis);
            if (doc.kind.isNote()) {
                requireWithinUncredited(connection, doc);
                requireItemsWithinOriginal(connection, doc);
            }
            List<DocumentLine> docLines = loadLines(connection, documentId);
            requireOrdersOpen(connection, documentId);
            boolean movesStock = docLines.stream().anyMatch(line -> line.warehouseId() != null);
            if (movesStock) {
                entitlement.requireWritable(INVENTORY, "move stock for a document");
            }

            String number = DocumentNumbers.format(doc.kind, year.start(), year.end(),
                    Lookups.nextSequence(connection, DocumentNumbers.scope(doc.kind, year.id()), now()));

            RoundOff money = new RoundOff(doc.amount, doc.total, doc.roundOff);
            String narration = label(doc.kind) + " " + number + " - " + partyName(connection, doc.partyId);
            boolean creditable = creditable(basis);
            BigDecimal tax = docLines.stream().map(DocumentLine::tax).reduce(BigDecimal.ZERO, BigDecimal::add);
            Withheld withheld = withhold(connection, doc);
            List<PostingPlan.TaxLeg> legs = new ArrayList<>(taxLegs(connection, basis, docLines));
            BigDecimal partyAmount = doc.total;
            if (withheld != null && withheld.amount().signum() > 0) {
                boolean tds = side == DocumentKind.Side.PURCHASE;
                legs.add(new PostingPlan.TaxLeg(Lookups.ledgerByCode(connection, tds ? "TDS_PAYABLE" : "TCS_PAYABLE"),
                        withheld.amount(), !tds));
                partyAmount = tds ? doc.total.subtract(withheld.amount()) : doc.total.add(withheld.amount());
            }
            List<EntryLine> entries = PostingPlan.entries(doc.kind, Lookups.partyLedger(connection, doc.partyId),
                    partyAmount, Lookups.ledgerByCode(connection, side.counterLedgerCode()),
                    creditable ? doc.taxable : doc.taxable.add(tax), Lookups.ledgerByCode(connection, "ROUND_OFF"),
                    money, legs, narration);
            PostedVoucher voucher = posting.postWithin(connection, new PostingRequest(module, year.id(),
                    Lookups.voucherType(connection, doc.kind.voucherTypeCode()), doc.date, number, narration, entries,
                    "doc:" + documentId));

            for (DocumentLine line : docLines) {
                if (line.warehouseId() != null) {
                    // A note's goods undo its original's: valued at that cost.
                    stockPort().move(connection, doc.kind.stockKind(), line.itemId(), line.warehouseId(),
                            line.quantity(), unitCost(doc.kind, line, creditable), doc.date, doc.kind.name(), documentId,
                            number,
                            doc.kind.isNote() ? doc.kind.parent().name() : null,
                            doc.kind.isNote() ? doc.originalId : null);
                }
            }

            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + documents
                    + " SET status = 'POSTED', number = ?, fiscal_year_id = ?, voucher_id = ?, updated_at = ?, "
                    + "withholding_section = ?, tds_e4 = ?, tcs_e4 = ? WHERE id = ?")) {
                long withheldE4 = withheld == null ? 0 : MoneyMapper.toStored(withheld.amount(), Scale.MONEY);
                ps.setString(1, number);
                ps.setString(2, year.id());
                ps.setString(3, voucher.voucherId());
                ps.setLong(4, now());
                ps.setString(5, withheld == null ? null : withheld.section());
                ps.setLong(6, side == DocumentKind.Side.PURCHASE ? withheldE4 : 0);
                ps.setLong(7, side == DocumentKind.Side.SALES ? withheldE4 : 0);
                ps.setString(8, documentId);
                ps.executeUpdate();
            }
            if (withheld != null) {
                insertWithholding(connection, doc, withheld);
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
        long noteSettleE4;
        try (PreparedStatement ps = connection.prepareStatement("SELECT d.settle_e4 - COALESCE((SELECT SUM(amount_e4) "
                + "FROM " + allocations + " WHERE document_id = d.id AND removed_date IS NULL), 0), "
                + "(SELECT settle_e4 FROM " + documents + " WHERE id = ?) FROM " + documents + " d WHERE d.id = ?")) {
            ps.setString(1, note.id);
            ps.setString(2, note.originalId);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                outstandingE4 = rows.getLong(1);
                noteSettleE4 = rows.getLong(2);
            }
        }
        // What the note settles: its total less the TDS it reverses, plus the TCS.
        long apply = Math.min(outstandingE4, noteSettleE4);
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
                    // Cancellation undoes this document's own movements, at their cost.
                    stockPort().move(connection, doc.kind.cancellationStockKind(), line.itemId(), line.warehouseId(),
                            line.quantity(), null, cancellationDate, doc.kind.name() + "_CANCEL", documentId,
                            "Cancellation of " + doc.number, doc.kind.name(), documentId);
                }
            }

            try (PreparedStatement ps = connection.prepareStatement("UPDATE withholding_entry SET status = "
                    + "'CANCELLED', cancelled_date = ? WHERE source_type = ? AND source_id = ? AND status = 'ACTIVE'")) {
                ps.setString(1, cancellationDate.toString());
                ps.setString(2, withholdingSource());
                ps.setString(3, documentId);
                ps.executeUpdate();
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

    private record Draft(LocalDate dueDate, RoundOff money, List<LineValues> lines, BigDecimal taxable,
                         BigDecimal tax, GstBasis gst, String withholdingSection) {
    }

    private record LineValues(String itemId, String description, BigDecimal quantity, BigDecimal rate,
                              int discountBp, BigDecimal amount, String warehouseId, String orderLineId,
                              TaxCategory taxCategory, BigDecimal gstRate, BigDecimal cessRate, String hsnSac,
                              List<LineTax> taxes) {
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
        if (request.quotationId() != null && (side != DocumentKind.Side.SALES || request.kind().isNote())) {
            throw new TradeException("Only a sales invoice is made from a quotation");
        }
        boolean linksOrders = request.lines().stream().anyMatch(l -> l.orderLineId() != null);
        if (linksOrders && request.kind().isNote()) {
            throw new TradeException("A note corrects a document; it does not fulfil an order");
        }

        Header original = null;
        if (request.kind().isNote()) {
            if (request.originalDocumentId() == null) {
                throw new TradeException("A " + label(request.kind()) + " must name the "
                        + label(request.kind().parent()) + " it corrects");
            }
            original = header(connection, request.originalDocumentId());
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
        GstContext gst = gstContext(connection, request.kind(), party, request.date(), blankToNull(request.placeOfSupply()),
                request.reverseCharge(), original);
        List<DocumentLine> originalLines = original == null ? List.of() : loadLines(connection, original.id);
        List<LineValues> values = new ArrayList<>();
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        int number = 1;
        for (LineRequest line : request.lines()) {
            if (line.orderLineId() != null) {
                requireOrderLine(connection, line, request.partyId());
            }
            LineValues value = validateLine(connection, line, number++, gst, originalLines);
            values.add(value);
            taxable = taxable.add(value.amount);
            for (LineTax t : value.taxes) {
                tax = tax.add(t.amount());
            }
        }
        // Under reverse charge the company pays the tax itself; the vendor is owed the taxable value.
        BigDecimal amount = gst.basis().reverseCharge() ? taxable : taxable.add(tax);

        LocalDate dueDate = request.dueDate();
        if (dueDate == null && !request.kind().isNote() && party.creditDays != null) {
            dueDate = request.date().plusDays(party.creditDays);
        }
        if (dueDate != null && dueDate.isBefore(request.date())) {
            throw new TradeException("A due date cannot be before the document date");
        }
        String withholding = withholdingSection(connection, request, party, original);
        return new Draft(dueDate, RoundOff.of(amount, request.roundOff()), values, taxable, tax, gst.basis(),
                withholding);
    }

    private LineValues validateLine(Connection connection, LineRequest line, int number, GstContext gst,
                                    List<DocumentLine> originalLines) throws SQLException {
        String description = blankToNull(line.description());
        String warehouse = line.warehouseId();
        String hsnSac = null;
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
            hsnSac = item.hsnSac();
        }
        if (line.quantity() != null && line.quantity().stripTrailingZeros().scale() > Scale.QUANTITY.digits()) {
            throw new TradeException("A quantity may have at most four decimal places");
        }
        BigDecimal amount = LineMath.amount(line.quantity(), line.rate(), line.discountBp());
        String where = "Line " + number + " (" + description + ")";
        if (!gst.taxed()) {
            if (line.taxCategory() == TaxCategory.TAXABLE) {
                throw new TradeException(where + " cannot carry GST: " + gst.untaxedReason() + ".");
            }
            return new LineValues(line.itemId(), description, line.quantity(), line.rate(), line.discountBp(), amount,
                    warehouse, line.orderLineId(), null, BigDecimal.ZERO, BigDecimal.ZERO, hsnSac, List.of());
        }

        // P9-2: stated on the line, else the original line's (a note), else the item's on the document date.
        TaxCategory category = line.taxCategory();
        BigDecimal gstRate = line.gstRate();
        BigDecimal cessRate = line.cessRate();
        if (category == null && line.itemId() != null) {
            if (!originalLines.isEmpty()) {
                Optional<DocumentLine> was = originalLines.stream()
                        .filter(l -> line.itemId().equals(l.itemId()) && l.taxCategory() != null).findFirst();
                if (was.isPresent()) {
                    category = was.get().taxCategory();
                    gstRate = was.get().gstRate();
                    cessRate = was.get().cessRate();
                }
            } else {
                Optional<StockPort.ItemTax> itemTax = stockPort().taxOn(connection, line.itemId(), gst.rateDate());
                if (itemTax.isPresent()) {
                    category = TaxCategory.valueOf(itemTax.get().category());
                    gstRate = itemTax.get().gstRate();
                    cessRate = itemTax.get().cessRate();
                }
            }
        }
        if (category == null) {
            throw new TradeException(where + " needs its GST category - taxable, nil-rated, exempt or non-GST"
                    + (line.itemId() == null ? "." : originalLines.isEmpty()
                    ? ". Record the item's GST from " + gst.rateDate() + " or earlier, or state it on the line."
                    : ". The document it corrects does not carry this item; state its GST on the line."));
        }
        gstRate = gstRate == null ? BigDecimal.ZERO : gstRate.stripTrailingZeros();
        cessRate = cessRate == null ? BigDecimal.ZERO : cessRate.stripTrailingZeros();
        try {
            GstCalculator.requireValid(category, gstRate, cessRate, gst.rateDate(), gst.rules());
        } catch (RulePackException e) {
            throw new TradeException(where + ": " + e.getMessage(), e);
        }
        List<LineTax> taxes = GstCalculator.lineTax(amount, category, gstRate, cessRate, gst.basis().supplyType(),
                gst.placeKind());
        return new LineValues(line.itemId(), description, line.quantity(), line.rate(), line.discountBp(), amount,
                warehouse, line.orderLineId(), category, gstRate, cessRate, hsnSac, taxes);
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

    /** A linked order line must be on a confirmed order of the same party, for the same item. */
    private void requireOrderLine(Connection connection, LineRequest line, String partyId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT o.status, o.party_id, l.item_id, o.number "
                + "FROM " + orderLines + " l JOIN " + orders + " o ON o.id = l.order_id WHERE l.id = ?")) {
            ps.setString(1, line.orderLineId());
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No such order line: " + line.orderLineId());
                }
                if (!"CONFIRMED".equals(rows.getString(1))) {
                    throw new TradeException("Order " + rows.getString(4) + " is not open for fulfilment");
                }
                if (!rows.getString(2).equals(partyId)) {
                    throw new TradeException("An order is fulfilled for the party that placed it");
                }
                if (!rows.getString(3).equals(line.itemId())) {
                    throw new TradeException("A line fulfilling an order must carry the ordered item");
                }
            }
        }
    }

    /**
     * P7-20: posting may not fulfil more than an order line has open, and an
     * order closed or cancelled since the draft was made fulfils nothing more.
     */
    private void requireOrdersOpen(Connection connection, String documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT o.number, o.status, ol.description, ol.quantity_e4,
                       COALESCE((SELECT SUM(dl.quantity_e4) FROM %1$s dl JOIN %2$s d ON d.id = dl.document_id
                                 WHERE dl.order_line_id = ol.id AND d.status = 'POSTED'), 0) AS fulfilled,
                       mine.q
                FROM (SELECT order_line_id, SUM(quantity_e4) AS q FROM %1$s
                      WHERE document_id = ? AND order_line_id IS NOT NULL GROUP BY order_line_id) mine
                JOIN %3$s ol ON ol.id = mine.order_line_id
                JOIN %4$s o ON o.id = ol.order_id
                WHERE o.status <> 'CONFIRMED' OR mine.q + 0 > ol.quantity_e4 - (SELECT COALESCE(SUM(dl.quantity_e4), 0)
                      FROM %1$s dl JOIN %2$s d ON d.id = dl.document_id
                      WHERE dl.order_line_id = ol.id AND d.status = 'POSTED')
                LIMIT 1
                """.formatted(lines, documents, orderLines, orders))) {
            ps.setString(1, documentId);
            try (ResultSet rows = ps.executeQuery()) {
                if (rows.next()) {
                    if (!"CONFIRMED".equals(rows.getString(2))) {
                        throw new TradeException("Order " + rows.getString(1) + " is " + rows.getString(2)
                                .toLowerCase(java.util.Locale.ROOT) + " and can no longer be fulfilled");
                    }
                    BigDecimal open = MoneyMapper.fromStored(rows.getLong(4) - rows.getLong(5), Scale.QUANTITY);
                    throw new TradeException("Order " + rows.getString(1) + " has only " + plain(open) + " of \""
                            + rows.getString(3) + "\" still open; this would fulfil "
                            + plain(MoneyMapper.fromStored(rows.getLong(6), Scale.QUANTITY))
                            + ". Put the extra on a separate line.");
                }
            }
        }
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

    /**
     * Purchase side records what a unit cost, for valuation in P8; sales records
     * none. GST that cannot be credited is part of the cost (AS 2).
     */
    private static BigDecimal unitCost(DocumentKind kind, DocumentLine line, boolean taxCreditable) {
        if (kind.side() != DocumentKind.Side.PURCHASE || !kind.stockKind().bringsStockIn()) {
            return null;
        }
        BigDecimal cost = taxCreditable ? line.amount() : line.amount().add(line.tax());
        return cost.divide(line.quantity(), Scale.MONEY.digits(), RoundingMode.HALF_UP);
    }

    // ======================================================= TDS and TCS (P9c)

    private WithholdingSection.Kind withholdingKind() {
        return side == DocumentKind.Side.PURCHASE ? WithholdingSection.Kind.TDS : WithholdingSection.Kind.TCS;
    }

    private String withholdingSource() {
        return side == DocumentKind.Side.PURCHASE ? "PURCHASE_DOCUMENT" : "SALES_DOCUMENT";
    }

    /**
     * The section a draft names: checked to be in force on its date for this
     * party. A note takes its original's, so it reverses what was withheld.
     */
    private String withholdingSection(Connection connection, DraftRequest request, PartyRow party, Header original)
            throws SQLException {
        String chosen = blankToNull(request.withholdingSection());
        if (original != null) {
            String was = Lookups.single(connection, "SELECT COALESCE(withholding_section, '') FROM " + documents
                    + " WHERE id = ?", original.id, "No such document").transform(v -> v.isEmpty() ? null : v);
            if (chosen != null && !chosen.equalsIgnoreCase(was == null ? "" : was)
                    && (was == null || !was.toUpperCase(java.util.Locale.ROOT)
                    .startsWith(chosen.toUpperCase(java.util.Locale.ROOT) + "-"))) {
                throw new TradeException("A note follows " + original.number + " on "
                        + withholdingKind() + (was == null ? ", which had none" : " (" + was + ")"));
            }
            return was;
        }
        if (chosen == null) {
            return null;
        }
        if (party.pan != null && Pan.problem(party.pan).isPresent()) {
            throw new TradeException("\"" + party.name + "\" has an invalid PAN: " + Pan.problem(party.pan).get()
                    + ". Correct it, or clear it to deduct at the no-PAN rate.");
        }
        try {
            Withholding.resolve(rules.get(), withholdingKind(), chosen, party.pan, request.date());
        } catch (RulePackException e) {
            throw new TradeException(e.getMessage(), e);
        }
        return chosen.toUpperCase(java.util.Locale.ROOT);
    }

    /** TDS or TCS worked out at posting, to be recorded. Amounts are signed: a note's are negative. */
    private record Withheld(String section, String pan, BigDecimal base, BigDecimal taxedBase, BigDecimal rate,
                            BigDecimal signedAmount, String rulePackVersion) {
        BigDecimal amount() {
            return signedAmount.abs();
        }
    }

    /**
     * Worked out at posting, not on the draft: the threshold depends on what
     * the party has had this year (or month) under the section, which other
     * documents posted meanwhile change. TDS is on the value before GST (CBDT
     * Circular 23/2017); TCS on the whole amount received.
     */
    private Withheld withhold(Connection connection, Header doc) throws SQLException {
        String code = Lookups.single(connection, "SELECT COALESCE(withholding_section, '') FROM " + documents
                + " WHERE id = ?", doc.id, "No such document");
        if (code.isEmpty()) {
            return null;
        }
        PartyRow party = party(connection, doc.partyId);
        BigDecimal base = side == DocumentKind.Side.PURCHASE ? doc.taxable : doc.total;
        RulePack pack = rules.get();
        if (doc.kind.isNote()) {
            return reverseOnNote(connection, doc, code, party, base, pack);
        }
        WithholdingSection section;
        try {
            section = Withholding.resolve(pack, withholdingKind(), code, party.pan, doc.date);
        } catch (RulePackException e) {
            throw new TradeException(e.getMessage(), e);
        }
        LocalDate from = section.thresholdType() == WithholdingSection.ThresholdType.MONTHLY
                ? doc.date.withDayOfMonth(1) : Withholding.yearStart(doc.date);
        LocalDate to = section.thresholdType() == WithholdingSection.ThresholdType.MONTHLY
                ? from.plusMonths(1).minusDays(1) : from.plusYears(1).minusDays(1);
        Withholding.Prior prior;
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(base_e4), 0), "
                + "COALESCE(SUM(taxed_base_e4), 0) FROM withholding_entry WHERE party_id = ? AND kind = ? "
                + "AND direction = 'PAYABLE' AND section = ? AND status = 'ACTIVE' AND entry_date BETWEEN ? AND ?")) {
            ps.setString(1, doc.partyId);
            ps.setString(2, withholdingKind().name());
            ps.setString(3, section.section());
            ps.setString(4, from.toString());
            ps.setString(5, to.toString());
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                prior = new Withholding.Prior(MoneyMapper.fromStored(rows.getLong(1), Scale.MONEY),
                        MoneyMapper.fromStored(rows.getLong(2), Scale.MONEY));
            }
        }
        Withholding.Result result = Withholding.compute(section, party.pan, base, prior);
        if (side == DocumentKind.Side.PURCHASE && result.amount().compareTo(doc.total) > 0) {
            throw new TradeException("TDS of " + result.amount().toPlainString() + " would exceed the bill's total");
        }
        return new Withheld(section.section(), party.pan, base, result.taxedBase(), result.rate(), result.amount(),
                pack.version());
    }

    /**
     * A note reverses its original's withholding at the original's rate, on the
     * note's own base, never beyond what is left of the original's amount. An
     * original below the threshold had nothing withheld, so neither has its note.
     */
    private Withheld reverseOnNote(Connection connection, Header note, String section, PartyRow party,
                                   BigDecimal base, RulePack pack) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT o.rate_e4, o.amount_e4 + COALESCE((SELECT SUM(n.amount_e4) FROM withholding_entry n
                           JOIN %1$s d ON d.id = n.source_id
                           WHERE n.source_type = o.source_type AND d.original_document_id = o.source_id
                             AND n.status = 'ACTIVE'), 0),
                       o.taxed_base_e4
                FROM withholding_entry o WHERE o.source_type = ? AND o.source_id = ? AND o.status = 'ACTIVE'
                """.formatted(documents))) {
            ps.setString(1, withholdingSource());
            ps.setString(2, note.originalId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("The document this note corrects has no " + withholdingKind()
                            + " record to reverse");
                }
                BigDecimal rate = MoneyMapper.fromStored(rows.getLong(1), Scale.TAX_RATE).stripTrailingZeros();
                BigDecimal remaining = MoneyMapper.fromStored(rows.getLong(2), Scale.MONEY);
                boolean taxed = rows.getLong(3) > 0;
                BigDecimal amount = taxed
                        ? base.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).min(remaining)
                        : BigDecimal.ZERO;
                return new Withheld(section, party.pan, base.negate(), taxed ? base.negate() : BigDecimal.ZERO, rate,
                        amount.negate(), pack.version());
            }
        }
    }

    private void insertWithholding(Connection connection, Header doc, Withheld w) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO withholding_entry (id, kind, direction, "
                + "source_type, source_id, party_id, section, pan, entry_date, base_e4, taxed_base_e4, rate_e4, "
                + "amount_e4, rule_pack_version, created_at) VALUES (?, ?, 'PAYABLE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, Ids.newIdString());
            ps.setString(2, withholdingKind().name());
            ps.setString(3, withholdingSource());
            ps.setString(4, doc.id);
            ps.setString(5, doc.partyId);
            ps.setString(6, w.section());
            ps.setString(7, w.pan() == null ? null : Pan.normalise(w.pan()));
            ps.setString(8, doc.date.toString());
            ps.setLong(9, MoneyMapper.toStored(w.base(), Scale.MONEY));
            ps.setLong(10, MoneyMapper.toStored(w.taxedBase(), Scale.MONEY));
            ps.setLong(11, MoneyMapper.toStored(w.rate(), Scale.TAX_RATE));
            ps.setLong(12, MoneyMapper.toStored(w.signedAmount(), Scale.MONEY));
            ps.setString(13, w.rulePackVersion());
            ps.setLong(14, now());
            ps.executeUpdate();
        }
    }

    // =================================================================== GST

    /**
     * Whether this document's purchase GST is input credit. A regular company
     * credits it; a composition or unregistered one bears it as cost. On the
     * sales side the question does not arise: output tax is never cost.
     */
    private boolean creditable(GstBasis basis) {
        return side == DocumentKind.Side.SALES || basis.scheme() == GstRegistration.Scheme.REGULAR;
    }

    /**
     * The tax postings: output GST on sales, input GST on creditable purchases,
     * and under reverse charge the same tax payable by the company. Each
     * component to its own ledger (P9-7).
     */
    private List<PostingPlan.TaxLeg> taxLegs(Connection connection, GstBasis basis, List<DocumentLine> docLines)
            throws SQLException {
        Map<GstComponent, BigDecimal> byComponent = new EnumMap<>(GstComponent.class);
        for (DocumentLine line : docLines) {
            for (LineTax tax : line.taxes()) {
                byComponent.merge(tax.component(), tax.amount(), BigDecimal::add);
            }
        }
        List<PostingPlan.TaxLeg> legs = new ArrayList<>();
        for (Map.Entry<GstComponent, BigDecimal> entry : byComponent.entrySet()) {
            String component = entry.getKey().name();
            if (side == DocumentKind.Side.SALES) {
                legs.add(new PostingPlan.TaxLeg(Lookups.ledgerByCode(connection, "GST_OUTPUT_" + component),
                        entry.getValue(), true));
                continue;
            }
            if (creditable(basis)) {
                legs.add(new PostingPlan.TaxLeg(Lookups.ledgerByCode(connection, "GST_INPUT_" + component),
                        entry.getValue(), true));
            }
            if (basis.reverseCharge()) {
                legs.add(new PostingPlan.TaxLeg(Lookups.ledgerByCode(connection, "GST_RCM_" + component),
                        entry.getValue(), false));
            }
        }
        return legs;
    }

    /** What the GST position of a draft is: everything but its lines. */
    private record GstContext(GstBasis basis, boolean taxed, StateCode.Kind placeKind, LocalDate rateDate,
                              RulePack rules, String untaxedReason) {
    }

    /**
     * Work out a document's GST basis (P9-5, P9-8). A note takes its original's,
     * so it reverses tax exactly as it was charged.
     */
    private GstContext gstContext(Connection connection, DocumentKind kind, PartyRow party, LocalDate date,
                                  String chosenPlace, boolean reverseCharge, Header original) throws SQLException {
        RulePack pack = rules.get();
        GstRegistration registration = GstRegistrations.on(connection, date).orElseThrow(() -> new TradeException(
                "Record the company's GST registration - regular, composition or unregistered - in force on "
                        + date + " before making a " + label(kind) + "."));
        if (reverseCharge && side == DocumentKind.Side.SALES) {
            throw new TradeException("Reverse charge is for purchases: the buyer pays the tax, not the seller");
        }
        GstBasis basis;
        LocalDate rateDate = date;
        try {
            if (original != null) {
                GstBasis was = basis(connection, original.id).orElseThrow(() -> new TradeException(original.number
                        + " was posted before documents carried GST. Correct it with a journal voucher."));
                if (chosenPlace != null && !chosenPlace.strip().equals(was.placeOfSupply())) {
                    throw new TradeException("A note keeps the place of supply of " + original.number
                            + " (" + was.placeOfSupply() + "), the document it corrects");
                }
                if (reverseCharge != was.reverseCharge()) {
                    throw new TradeException("A note follows " + original.number + " on reverse charge");
                }
                basis = new GstBasis(was.scheme(), was.placeOfSupply(), was.supplierState(), was.supplyType(),
                        was.partyGstin(), was.reverseCharge(), pack.version());
                rateDate = original.date;
            } else {
                String gstin = null;
                if (party.gstin != null) {
                    Optional<String> problem = Gstin.problem(party.gstin, pack);
                    if (problem.isPresent()) {
                        throw new TradeException("\"" + party.name + "\" has an invalid GSTIN: " + problem.get());
                    }
                    gstin = Gstin.normalise(party.gstin);
                }
                PlaceOfSupply place = side == DocumentKind.Side.SALES
                        ? PlaceOfSupply.forSale(registration.stateCode(), gstin, party.stateCode, chosenPlace, pack)
                        : PlaceOfSupply.forPurchase(registration.stateCode(), gstin, party.stateCode, chosenPlace, pack);
                if (reverseCharge && registration.scheme() == GstRegistration.Scheme.UNREGISTERED) {
                    throw new TradeException("An unregistered business does not pay GST under reverse charge");
                }
                if (side == DocumentKind.Side.SALES && registration.scheme() == GstRegistration.Scheme.COMPOSITION
                        && place.supplyType() == SupplyType.INTER_STATE) {
                    throw new TradeException("A composition dealer cannot supply to another state (place of supply "
                            + place.placeOfSupply() + ")");
                }
                basis = new GstBasis(registration.scheme(), place.placeOfSupply(), place.supplierState(),
                        place.supplyType(), gstin, reverseCharge, pack.version());
            }
            StateCode.Kind placeKind = pack.state(basis.placeOfSupply()).map(StateCode::kind).orElseThrow(() ->
                    new TradeException("Place of supply " + basis.placeOfSupply() + " is not in rule pack "
                            + pack.version()));
            String untaxed = untaxedReason(basis, party.name);
            return new GstContext(basis, untaxed == null, placeKind, rateDate, pack, untaxed);
        } catch (RulePackException e) {
            throw new TradeException(e.getMessage(), e);
        }
    }

    /**
     * P9-9: a composition dealer leaves the scheme on the day its turnover for
     * the financial year exceeds the limit, and must issue tax invoices from
     * then. So the bill of supply that would take it over is refused; the user
     * records a regular registration from that date and the invoice is taxed.
     * Turnover here is this company file's posted invoices less credit notes.
     */
    private void requireWithinCompositionLimit(Connection connection, Header doc, GstBasis basis)
            throws SQLException {
        if (doc.kind != DocumentKind.SALES_INVOICE || basis.scheme() != GstRegistration.Scheme.COMPOSITION) {
            return;
        }
        LocalDate from = Withholding.yearStart(doc.date);
        long soFarE4;
        try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(CASE kind WHEN 'SALES_INVOICE' "
                + "THEN taxable_e4 ELSE -taxable_e4 END), 0) FROM sales_document WHERE status = 'POSTED' "
                + "AND document_date BETWEEN ? AND ?")) {
            ps.setString(1, from.toString());
            ps.setString(2, from.plusYears(1).minusDays(1).toString());
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                soFarE4 = rows.getLong(1);
            }
        }
        BigDecimal turnover = MoneyMapper.fromStored(soFarE4, Scale.MONEY).add(doc.taxable);
        BigDecimal limit;
        try {
            limit = rules.get().compositionLimit(basis.supplierState(), doc.date);
        } catch (RulePackException e) {
            throw new TradeException(e.getMessage(), e);
        }
        if (turnover.compareTo(limit) > 0) {
            throw new TradeException("This invoice takes the year's turnover to " + plain(turnover)
                    + ", above the composition limit of " + plain(limit) + ". A composition dealer leaves the scheme "
                    + "from that day: record a regular GST registration from " + doc.date + ", then save and post "
                    + "this invoice again as a tax invoice.");
        }
    }

    /** Why a document bears no GST, or null if it does. */
    private String untaxedReason(GstBasis basis, String partyName) {
        if (side == DocumentKind.Side.SALES) {
            return switch (basis.scheme()) {
                case REGULAR -> null;
                case COMPOSITION -> "a composition dealer issues a bill of supply, without GST";
                case UNREGISTERED -> "an unregistered business cannot charge GST";
            };
        }
        if (basis.partyGstin() == null && !basis.reverseCharge()) {
            return "\"" + partyName + "\" has no GSTIN, so it charges no GST. Record its GSTIN, or mark the bill "
                    + "reverse charge";
        }
        return null;
    }

    /**
     * P9-8 at posting: the basis a draft was saved with must still be the one
     * its date, party and rule pack give. Otherwise the posted tax would differ
     * from the tax the draft showed - so the draft is saved again first.
     */
    private GstBasis requireBasisCurrent(Connection connection, Header doc) throws SQLException {
        GstBasis stored = basis(connection, doc.id).orElseThrow(() -> new TradeException("This draft was saved "
                + "before documents carried GST. Save it again so its tax is worked out."));
        Header original = doc.kind.isNote() ? header(connection, doc.originalId) : null;
        GstBasis now = gstContext(connection, doc.kind, party(connection, doc.partyId), doc.date,
                stored.placeOfSupply(), stored.reverseCharge(), original).basis();
        if (!now.equals(stored)) {
            throw new TradeException("The GST position of this draft has changed since it was saved - the "
                    + "company's registration, the party's GSTIN or the rule pack. Save it again to recompute its "
                    + "tax, then post it.");
        }
        return stored;
    }

    private Optional<GstBasis> basis(Connection connection, String documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT gst_scheme, place_of_supply, supplier_state, "
                + "supply_type, party_gstin, reverse_charge, rule_pack_version FROM " + documents + " WHERE id = ?")) {
            ps.setString(1, documentId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next() || rows.getString(1) == null) {
                    return Optional.empty();
                }
                return Optional.of(new GstBasis(GstRegistration.Scheme.valueOf(rows.getString(1)), rows.getString(2),
                        rows.getString(3), SupplyType.valueOf(rows.getString(4)), rows.getString(5),
                        rows.getInt(6) == 1, rows.getString(7)));
            }
        }
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

    /** Nine parameters from {@code start}: taxable, tax and the GST basis. */
    private static void bindGst(PreparedStatement ps, int start, Draft draft) throws SQLException {
        GstBasis gst = draft.gst();
        ps.setLong(start, MoneyMapper.toStored(draft.taxable(), Scale.MONEY));
        ps.setLong(start + 1, MoneyMapper.toStored(draft.tax(), Scale.MONEY));
        ps.setString(start + 2, gst.placeOfSupply());
        ps.setString(start + 3, gst.supplierState());
        ps.setString(start + 4, gst.supplyType().name());
        ps.setString(start + 5, gst.scheme().name());
        ps.setString(start + 6, gst.partyGstin());
        ps.setInt(start + 7, gst.reverseCharge() ? 1 : 0);
        ps.setString(start + 8, gst.rulePackVersion());
    }

    private void insertLines(Connection connection, String documentId, List<LineValues> values) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + lines
                + " (id, document_id, sequence, item_id, description, quantity_e4, rate_e4, discount_bp, "
                + "amount_e4, warehouse_id, order_line_id, tax_category, gst_rate_e4, cess_rate_e4, hsn_sac) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int sequence = 1;
            for (LineValues line : values) {
                String lineId = Ids.newIdString();
                ps.setString(1, lineId);
                ps.setString(2, documentId);
                ps.setInt(3, sequence++);
                ps.setString(4, line.itemId);
                ps.setString(5, line.description);
                ps.setLong(6, MoneyMapper.toStored(line.quantity.stripTrailingZeros(), Scale.QUANTITY));
                ps.setLong(7, MoneyMapper.toStored(line.rate.stripTrailingZeros(), Scale.MONEY));
                ps.setInt(8, line.discountBp);
                ps.setLong(9, MoneyMapper.toStored(line.amount, Scale.MONEY));
                ps.setString(10, line.warehouseId);
                ps.setString(11, line.orderLineId);
                ps.setString(12, line.taxCategory == null ? null : line.taxCategory.name());
                ps.setLong(13, MoneyMapper.toStored(line.gstRate, Scale.TAX_RATE));
                ps.setLong(14, MoneyMapper.toStored(line.cessRate, Scale.TAX_RATE));
                ps.setString(15, line.hsnSac);
                ps.executeUpdate();
                insertTaxLines(connection, lineId, line.taxes);
            }
        }
    }

    private void insertTaxLines(Connection connection, String lineId, List<LineTax> taxes) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + taxLines
                + " (id, line_id, component, rate_e4, amount_e4) VALUES (?, ?, ?, ?, ?)")) {
            for (LineTax tax : taxes) {
                ps.setString(1, Ids.newIdString());
                ps.setString(2, lineId);
                ps.setString(3, tax.component().name());
                ps.setLong(4, MoneyMapper.toStored(tax.rate(), Scale.TAX_RATE));
                ps.setLong(5, MoneyMapper.toStored(tax.amount(), Scale.MONEY));
                ps.executeUpdate();
            }
        }
    }

    private record Header(String id, DocumentKind kind, String number, String partyId, LocalDate date,
                          DocumentStatus status, String originalId, BigDecimal amount, BigDecimal roundOff,
                          BigDecimal total, String voucherId, BigDecimal taxable) {
    }

    private Header header(Connection connection, String documentId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, kind, number, party_id, document_date, "
                + "status, original_document_id, amount_e4, round_off_e4, total_e4, voucher_id, taxable_e4 FROM "
                + documents
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
                        MoneyMapper.fromStored(rows.getLong(10), Scale.MONEY), rows.getString(11),
                        MoneyMapper.fromStored(rows.getLong(12), Scale.MONEY));
            }
        }
    }

    private List<DocumentLine> loadLines(Connection connection, String documentId) throws SQLException {
        List<DocumentLine> result = new ArrayList<>();
        Map<String, List<LineTax>> taxesByLine = new java.util.HashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT t.line_id, t.component, t.rate_e4, "
                + "t.amount_e4 FROM " + taxLines + " t JOIN " + lines + " l ON l.id = t.line_id "
                + "WHERE l.document_id = ? ORDER BY t.rowid")) {
            ps.setString(1, documentId);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    taxesByLine.computeIfAbsent(rows.getString(1), k -> new ArrayList<>()).add(new LineTax(
                            GstComponent.valueOf(rows.getString(2)),
                            MoneyMapper.fromStored(rows.getLong(3), Scale.TAX_RATE).stripTrailingZeros(),
                            MoneyMapper.fromStored(rows.getLong(4), Scale.MONEY)));
                }
            }
        }
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, sequence, item_id, description, "
                + "quantity_e4, rate_e4, discount_bp, amount_e4, warehouse_id, order_line_id, tax_category, "
                + "gst_rate_e4, cess_rate_e4, hsn_sac FROM " + lines + " WHERE document_id = ? ORDER BY sequence")) {
            ps.setString(1, documentId);
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    String category = rows.getString(11);
                    result.add(new DocumentLine(rows.getString(1), rows.getInt(2), rows.getString(3),
                            rows.getString(4), MoneyMapper.fromStored(rows.getLong(5), Scale.QUANTITY),
                            MoneyMapper.fromStored(rows.getLong(6), Scale.MONEY), rows.getInt(7),
                            MoneyMapper.fromStored(rows.getLong(8), Scale.MONEY), rows.getString(9),
                            rows.getString(10), category == null ? null : TaxCategory.valueOf(category),
                            MoneyMapper.fromStored(rows.getLong(12), Scale.TAX_RATE).stripTrailingZeros(),
                            MoneyMapper.fromStored(rows.getLong(13), Scale.TAX_RATE).stripTrailingZeros(),
                            rows.getString(14), taxesByLine.getOrDefault(rows.getString(1), List.of())));
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
                + (side == DocumentKind.Side.PURCHASE ? "supplier_document_no" : "NULL") + ", narration, "
                + "taxable_e4, tax_e4, withholding_section, tds_e4, tcs_e4 FROM " + documents + where)) {
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
                            rows.getString(13), rows.getString(14), rows.getString(15), List.of(),
                            MoneyMapper.fromStored(rows.getLong(16), Scale.MONEY),
                            MoneyMapper.fromStored(rows.getLong(17), Scale.MONEY), null, rows.getString(18),
                            MoneyMapper.fromStored(rows.getLong(19), Scale.MONEY),
                            MoneyMapper.fromStored(rows.getLong(20), Scale.MONEY)));
                }
            }
        }
        List<Document> withLines = new ArrayList<>(result.size());
        for (Document d : result) {
            withLines.add(new Document(d.id(), d.kind(), d.number(), d.partyId(), d.date(), d.dueDate(), d.status(),
                    d.originalDocumentId(), d.amount(), d.roundOff(), d.total(), d.voucherId(),
                    d.cancellationVoucherId(), d.supplierDocumentNo(), d.narration(), loadLines(connection, d.id()),
                    d.taxable(), d.tax(), basis(connection, d.id()).orElse(null), d.withholdingSection(), d.tds(),
                    d.tcs()));
        }
        return withLines;
    }

    private record PartyRow(String name, String role, boolean active, Integer creditDays, String gstin,
                            String stateCode, String pan) {
    }

    private static PartyRow party(Connection connection, String partyId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT l.name, p.role, l.is_active, p.credit_days, "
                + "p.gstin, p.state_code, p.pan FROM party p JOIN ledger l ON l.id = p.ledger_id WHERE p.id = ?")) {
            ps.setString(1, partyId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No such party: " + partyId);
                }
                // wasNull() describes the column read last, so it is asked straight after credit_days.
                int days = rows.getInt(4);
                Integer creditDays = rows.wasNull() ? null : days;
                return new PartyRow(rows.getString(1), rows.getString(2), rows.getInt(3) == 1, creditDays,
                        blankToNull(rows.getString(5)), blankToNull(rows.getString(6)), blankToNull(rows.getString(7)));
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
