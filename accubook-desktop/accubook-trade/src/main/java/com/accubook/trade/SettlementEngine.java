package com.accubook.trade;

import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.ReversalRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleTransactions;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePackException;
import com.accubook.tax.RulePacks;
import com.accubook.tax.Withholding;
import com.accubook.tax.WithholdingSection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Receipts (sales side) and payments (purchase side), allocations, and the
 * settlement reports.
 *
 * <h2>Settlement is many-to-many (P7-3)</h2>
 * One receipt may pay several invoices; an invoice may be paid by several
 * receipts and notes; money not yet applied is an on-account advance. The
 * reference allows one invoice per receipt and an unlinked receipt reduces
 * nothing (R-6).
 *
 * <h2>Nothing is cached (R-7)</h2>
 * Outstanding amounts are computed from allocations. Every report takes an
 * as-of date and is correct for it: a document counts if it is dated on or
 * before that date and was not yet cancelled; an allocation counts from its
 * effective date until it was removed (R-12). Notes and receipts are never aged
 * as amounts owed (R-13); they appear as unallocated credits.
 */
public final class SettlementEngine {

    private final DocumentKind.Side side;
    private final ModuleId module;
    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final TransactionalPosting posting;
    private final Clock clock;
    private final Supplier<RulePack> rules;

    private final String settlements;
    private final String allocations;
    private final String documents;

    public SettlementEngine(DocumentKind.Side side, ModuleId module, ModuleTransactions transactions,
                            EntitlementGuard entitlement, TransactionalPosting posting, Clock clock) {
        this(side, module, transactions, entitlement, posting, RulePacks::bundledPack, clock);
    }

    /** @param rules the active rule pack, to check the section a customer deducted TDS under */
    public SettlementEngine(DocumentKind.Side side, ModuleId module, ModuleTransactions transactions,
                            EntitlementGuard entitlement, TransactionalPosting posting, Supplier<RulePack> rules,
                            Clock clock) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.side = Objects.requireNonNull(side, "side");
        this.module = Objects.requireNonNull(module, "module");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.posting = Objects.requireNonNull(posting, "posting");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.settlements = side.tablePrefix() + "_settlement";
        this.allocations = side.tablePrefix() + "_allocation";
        this.documents = side.tablePrefix() + "_document";
    }

    private boolean receipts() {
        return side == DocumentKind.Side.SALES;
    }

    private String noun() {
        return receipts() ? "receipt" : "payment";
    }

    // ================================================================ record

    /**
     * Record and post a receipt or payment, with its allocations, in one
     * transaction (P7-14). Refused allocations refuse the whole receipt.
     */
    public Settlement record(SettlementRequest request) {
        Objects.requireNonNull(request, "request");
        require("record a " + noun());
        String id = Ids.newIdString();
        write("record the " + noun(), connection -> {
            requireParty(connection, request.partyId());
            requireCashOrBank(connection, request.accountLedgerId());
            String tdsSection = tdsSection(request);
            BigDecimal allocated = request.allocations().stream()
                    .map(AllocationRequest::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (allocated.compareTo(request.amount()) > 0) {
                throw new TradeException("Allocations of " + plain(allocated) + " exceed the " + noun()
                        + " of " + plain(request.amount()));
            }
            Lookups.FiscalYearRow year = Lookups.fiscalYearFor(connection, request.date());
            String series = receipts() ? "RCT" : "PAY";
            String number = DocumentNumbers.format(series, year.start(), year.end(),
                    Lookups.nextSequence(connection, DocumentNumbers.scope(series, year.id()), now()));

            String partyLedger = Lookups.partyLedger(connection, request.partyId());
            String narration = (receipts() ? "Receipt " : "Payment ") + number
                    + (request.referenceNo() == null ? "" : " - " + request.referenceNo());
            // Receipt: Dr cash/bank, Cr customer. Payment: Dr vendor, Cr cash/bank.
            // TDS the customer deducted: the bank gets the rest, TDS Receivable the deduction.
            BigDecimal tds = request.tdsDeducted();
            List<EntryLine> entries = new ArrayList<>(3);
            if (receipts()) {
                entries.add(EntryLine.debit(request.accountLedgerId(), request.amount().subtract(tds)));
                if (tds.signum() > 0) {
                    entries.add(EntryLine.debit(Lookups.ledgerByCode(connection, "TDS_RECEIVABLE"), tds));
                }
                entries.add(EntryLine.credit(partyLedger, request.amount()));
            } else {
                entries.add(EntryLine.debit(partyLedger, request.amount()));
                entries.add(EntryLine.credit(request.accountLedgerId(), request.amount()));
            }
            PostedVoucher voucher = posting.postWithin(connection, new PostingRequest(module, year.id(),
                    Lookups.voucherType(connection, receipts() ? "RECEIPT" : "PAYMENT"), request.date(), number,
                    narration, entries, "settlement:" + id));

            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + settlements
                    + " (id, number, party_id, settlement_date, amount_e4, account_ledger_id, mode, reference_no, "
                    + "instrument_date, fiscal_year_id, voucher_id, narration, created_at, updated_at, tds_e4, "
                    + "tds_section) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, number);
                ps.setString(3, request.partyId());
                ps.setString(4, request.date().toString());
                ps.setLong(5, money(request.amount()));
                ps.setString(6, request.accountLedgerId());
                ps.setString(7, request.mode().name());
                ps.setString(8, blankToNull(request.referenceNo()));
                ps.setString(9, request.instrumentDate() == null ? null : request.instrumentDate().toString());
                ps.setString(10, year.id());
                ps.setString(11, voucher.voucherId());
                ps.setString(12, blankToNull(request.narration()));
                ps.setLong(13, now());
                ps.setLong(14, now());
                ps.setLong(15, money(tds));
                ps.setString(16, tdsSection);
                ps.executeUpdate();
            }
            if (tds.signum() > 0) {
                recordReceivable(connection, id, request, tdsSection);
            }
            for (AllocationRequest allocation : request.allocations()) {
                insertAllocation(connection, Allocation.SourceType.SETTLEMENT, id, request.date(), allocation);
            }
        });
        return find(id).orElseThrow();
    }

    /** Apply unallocated money from a receipt, payment or note to a document. */
    public void allocate(Allocation.SourceType sourceType, String sourceId, AllocationRequest allocation) {
        require("allocate a " + noun());
        write("allocate", connection -> {
            LocalDate sourceDate = sourceDate(connection, sourceType, sourceId);
            insertAllocation(connection, sourceType, sourceId, sourceDate, allocation);
        });
    }

    /**
     * Remove an allocation, dated {@code removedOn}. It stays on record: an
     * as-of report for an earlier date still sees it.
     */
    public void removeAllocation(String allocationId, String reason, LocalDate removedOn) {
        require("remove an allocation");
        if (reason == null || reason.isBlank()) {
            throw new TradeException("Removing an allocation needs a reason");
        }
        write("remove the allocation", connection -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + allocations
                    + " SET removed_date = MAX(effective_date, ?), removal_reason = ? "
                    + "WHERE id = ? AND removed_date IS NULL")) {
                ps.setString(1, removedOn.toString());
                ps.setString(2, reason.strip());
                ps.setString(3, allocationId);
                if (ps.executeUpdate() == 0) {
                    throw new TradeException("No such allocation, or it was already removed");
                }
            }
        });
    }

    /**
     * Cancel a receipt or payment - a bounced cheque, a wrong entry. Its
     * allocations are removed as of the cancellation date, so the documents it
     * paid are outstanding again from then; its voucher is reversed.
     */
    public Settlement cancel(String settlementId, String reason, LocalDate cancellationDate) {
        require("cancel a " + noun());
        if (reason == null || reason.isBlank()) {
            throw new TradeException("A cancellation needs a reason; it stays on the record");
        }
        Objects.requireNonNull(cancellationDate, "cancellationDate");
        write("cancel the " + noun(), connection -> {
            String voucherId;
            LocalDate date;
            String number;
            try (PreparedStatement ps = connection.prepareStatement("SELECT status, voucher_id, settlement_date, "
                    + "number FROM " + settlements + " WHERE id = ?")) {
                ps.setString(1, settlementId);
                try (ResultSet rows = ps.executeQuery()) {
                    if (!rows.next()) {
                        throw new TradeException("No such " + noun() + ": " + settlementId);
                    }
                    if (!"POSTED".equals(rows.getString(1))) {
                        throw new TradeException("This " + noun() + " is already cancelled");
                    }
                    voucherId = rows.getString(2);
                    date = LocalDate.parse(rows.getString(3));
                    number = rows.getString(4);
                }
            }
            if (cancellationDate.isBefore(date)) {
                throw new TradeException("A " + noun() + " cannot be cancelled before it was received");
            }
            Lookups.FiscalYearRow year = Lookups.fiscalYearFor(connection, cancellationDate);
            removeAllocationsOfSource(connection, Allocation.SourceType.SETTLEMENT, settlementId, cancellationDate,
                    "Cancelled: " + reason.strip());
            PostedVoucher reversal = posting.reverseWithin(connection, voucherId, new ReversalRequest(module,
                    year.id(), cancellationDate, "Cancelled " + number + ": " + reason.strip(), null,
                    "settlement-cancel:" + settlementId));
            try (PreparedStatement ps = connection.prepareStatement("UPDATE withholding_entry SET status = "
                    + "'CANCELLED', cancelled_date = ? WHERE source_type = 'SALES_SETTLEMENT' AND source_id = ? "
                    + "AND status = 'ACTIVE'")) {
                ps.setString(1, cancellationDate.toString());
                ps.setString(2, settlementId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("UPDATE " + settlements
                    + " SET status = 'CANCELLED', cancellation_voucher_id = ?, cancellation_reason = ?, updated_at = ? "
                    + "WHERE id = ?")) {
                ps.setString(1, reversal.voucherId());
                ps.setString(2, reason.strip());
                ps.setLong(3, now());
                ps.setString(4, settlementId);
                ps.executeUpdate();
            }
        });
        return find(settlementId).orElseThrow();
    }

    // ================================================================= reads

    public Optional<Settlement> find(String settlementId) {
        return read(connection -> {
            List<Settlement> found = settlementsWhere(connection, " WHERE s.id = ?", settlementId);
            return found.isEmpty() ? Optional.<Settlement>empty() : Optional.of(found.get(0));
        });
    }

    public List<Settlement> list() {
        return read(connection -> settlementsWhere(connection, " ORDER BY s.settlement_date DESC, s.created_at DESC"));
    }

    /** Every allocation against a document, live and removed. */
    public List<Allocation> allocationsOf(String documentId) {
        return read(connection -> {
            List<Allocation> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT id, source_type, source_id, document_id, "
                    + "amount_e4, effective_date, removed_date FROM " + allocations
                    + " WHERE document_id = ? ORDER BY effective_date, created_at")) {
                ps.setString(1, documentId);
                try (ResultSet rows = ps.executeQuery()) {
                    while (rows.next()) {
                        String removed = rows.getString(7);
                        result.add(new Allocation(rows.getString(1), Allocation.SourceType.valueOf(rows.getString(2)),
                                rows.getString(3), rows.getString(4),
                                MoneyMapper.fromStored(rows.getLong(5), Scale.MONEY),
                                LocalDate.parse(rows.getString(6)), removed == null ? null : LocalDate.parse(removed)));
                    }
                }
            }
            return result;
        });
    }

    /** What is still owed on a document at {@code asOf}. */
    public BigDecimal outstanding(String documentId, LocalDate asOf) {
        return outstanding(asOf).stream().filter(d -> d.documentId().equals(documentId))
                .map(OutstandingDocument::outstanding).findFirst().orElse(BigDecimal.ZERO.setScale(2));
    }

    public SettlementStatus status(String documentId, LocalDate today) {
        return read(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT settle_e4, due_date, document_date FROM "
                    + documents + " WHERE id = ? AND status = 'POSTED' AND original_document_id IS NULL")) {
                ps.setString(1, documentId);
                try (ResultSet rows = ps.executeQuery()) {
                    if (!rows.next()) {
                        throw new TradeException("Only a posted invoice or bill has a settlement status");
                    }
                    // What the party settles: total less TDS, plus TCS.
                    BigDecimal total = MoneyMapper.fromStored(rows.getLong(1), Scale.MONEY);
                    String due = rows.getString(2);
                    return SettlementStatus.of(total, outstanding(documentId, today),
                            due == null ? null : LocalDate.parse(due), today);
                }
            }
        });
    }

    /** Every document with something owed at {@code asOf}, oldest due first. */
    public List<OutstandingDocument> outstanding(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return read(connection -> {
            List<OutstandingDocument> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT d.id, d.number, d.party_id, d.document_date, d.due_date, d.total_e4,
                           d.settle_e4 - COALESCE((SELECT SUM(a.amount_e4) FROM %2$s a
                                                  WHERE a.document_id = d.id AND a.effective_date <= ?1
                                                    AND (a.removed_date IS NULL OR a.removed_date > ?1)), 0)
                               AS outstanding_e4
                    FROM %1$s d
                    WHERE d.original_document_id IS NULL
                      AND d.document_date <= ?1
                      AND (d.status = 'POSTED'
                           OR (d.status = 'CANCELLED'
                               AND (SELECT voucher_date FROM voucher WHERE id = d.cancellation_voucher_id) > ?1))
                    ORDER BY COALESCE(d.due_date, d.document_date), d.number
                    """.formatted(documents, allocations))) {
                ps.setString(1, asOf.toString());
                try (ResultSet rows = ps.executeQuery()) {
                    while (rows.next()) {
                        long outstandingE4 = rows.getLong(7);
                        if (outstandingE4 <= 0) {
                            continue;
                        }
                        LocalDate date = LocalDate.parse(rows.getString(4));
                        String due = rows.getString(5);
                        LocalDate dueDate = due == null ? null : LocalDate.parse(due);
                        long daysPast = ChronoUnit.DAYS.between(dueDate == null ? date : dueDate, asOf);
                        result.add(new OutstandingDocument(rows.getString(1), rows.getString(2), rows.getString(3),
                                date, dueDate, MoneyMapper.fromStored(rows.getLong(6), Scale.MONEY),
                                MoneyMapper.fromStored(outstandingE4, Scale.MONEY), Math.max(0, daysPast),
                                AgingBucket.of(daysPast)));
                    }
                }
            }
            return result;
        });
    }

    /** Receipts or payments, and notes, not yet applied at {@code asOf}. */
    public List<UnallocatedCredit> unallocatedCredits(LocalDate asOf) {
        Objects.requireNonNull(asOf, "asOf");
        return read(connection -> {
            List<UnallocatedCredit> result = new ArrayList<>();
            String live = "a.effective_date <= ?1 AND (a.removed_date IS NULL OR a.removed_date > ?1)";
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT 'SETTLEMENT', s.id, s.number, s.party_id, s.settlement_date,
                           s.amount_e4 - COALESCE((SELECT SUM(a.amount_e4) FROM %2$s a
                               WHERE a.source_type = 'SETTLEMENT' AND a.source_id = s.id AND %4$s), 0)
                    FROM %1$s s
                    WHERE s.settlement_date <= ?1
                      AND (s.status = 'POSTED'
                           OR (SELECT voucher_date FROM voucher WHERE id = s.cancellation_voucher_id) > ?1)
                    UNION ALL
                    SELECT 'NOTE', n.id, n.number, n.party_id, n.document_date,
                           n.settle_e4 - COALESCE((SELECT SUM(a.amount_e4) FROM %2$s a
                               WHERE a.source_type = 'NOTE' AND a.source_id = n.id AND %4$s), 0)
                    FROM %3$s n
                    WHERE n.original_document_id IS NOT NULL AND n.document_date <= ?1
                      AND (n.status = 'POSTED'
                           OR (n.status = 'CANCELLED'
                               AND (SELECT voucher_date FROM voucher WHERE id = n.cancellation_voucher_id) > ?1))
                    ORDER BY 5, 3
                    """.formatted(settlements, allocations, documents, live))) {
                ps.setString(1, asOf.toString());
                try (ResultSet rows = ps.executeQuery()) {
                    while (rows.next()) {
                        if (rows.getLong(6) <= 0) {
                            continue;
                        }
                        result.add(new UnallocatedCredit(Allocation.SourceType.valueOf(rows.getString(1)),
                                rows.getString(2), rows.getString(3), rows.getString(4),
                                LocalDate.parse(rows.getString(5)), MoneyMapper.fromStored(rows.getLong(6), Scale.MONEY)));
                    }
                }
            }
            return result;
        });
    }

    /** P7-15 aging at {@code asOf}, with advances alongside. */
    public Aging aging(LocalDate asOf) {
        return new Aging(asOf, outstanding(asOf), unallocatedCredits(asOf));
    }

    // ============================================================ TDS (P9c)

    /**
     * The section a customer deducted under, checked to be a TDS section in
     * force on the receipt's date. The customer decides the amount; it is
     * recorded as stated, to be matched against Form 26AS.
     */
    private String tdsSection(SettlementRequest request) {
        if (request.tdsDeducted().signum() == 0) {
            return null;
        }
        if (!receipts()) {
            throw new TradeException("TDS on a purchase is deducted on the bill, not on the payment");
        }
        String code = request.tdsSection().strip().toUpperCase(java.util.Locale.ROOT);
        try {
            Withholding.resolve(rules.get(), WithholdingSection.Kind.TDS, code, null, request.date());
        } catch (RulePackException e) {
            throw new TradeException(e.getMessage(), e);
        }
        return code;
    }

    private void recordReceivable(Connection connection, String settlementId, SettlementRequest request,
                                  String section) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO withholding_entry (id, kind, direction, "
                + "source_type, source_id, party_id, section, entry_date, base_e4, taxed_base_e4, rate_e4, amount_e4, "
                + "rule_pack_version, created_at) VALUES (?, 'TDS', 'RECEIVABLE', 'SALES_SETTLEMENT', ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?)")) {
            BigDecimal rate = request.tdsDeducted().multiply(BigDecimal.valueOf(100))
                    .divide(request.amount(), Scale.TAX_RATE.digits(), java.math.RoundingMode.HALF_UP);
            ps.setString(1, Ids.newIdString());
            ps.setString(2, settlementId);
            ps.setString(3, request.partyId());
            ps.setString(4, section);
            ps.setString(5, request.date().toString());
            ps.setLong(6, money(request.amount()));
            ps.setLong(7, money(request.amount()));
            ps.setLong(8, MoneyMapper.toStored(rate, Scale.TAX_RATE));
            ps.setLong(9, money(request.tdsDeducted()));
            ps.setString(10, rules.get().version());
            ps.setLong(11, now());
            ps.executeUpdate();
        }
    }

    // =============================================================== helpers

    private void insertAllocation(Connection connection, Allocation.SourceType sourceType, String sourceId,
                                  LocalDate sourceDate, AllocationRequest allocation) throws SQLException {
        LocalDate documentDate;
        String partyOfDocument;
        long outstandingE4;
        try (PreparedStatement ps = connection.prepareStatement("SELECT document_date, party_id, status, "
                + "original_document_id, number, settle_e4 - COALESCE((SELECT SUM(amount_e4) FROM " + allocations
                + " WHERE document_id = d.id AND removed_date IS NULL), 0) FROM " + documents + " d WHERE d.id = ?")) {
            ps.setString(1, allocation.documentId());
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next() || !"POSTED".equals(rows.getString(3)) || rows.getString(4) != null) {
                    throw new TradeException("Only a posted " + (receipts() ? "invoice" : "bill")
                            + " can be paid");
                }
                documentDate = LocalDate.parse(rows.getString(1));
                partyOfDocument = rows.getString(2);
                outstandingE4 = rows.getLong(6);
                if (money(allocation.amount()) > outstandingE4) {
                    throw new TradeException("Only " + plain(MoneyMapper.fromStored(outstandingE4, Scale.MONEY))
                            + " is outstanding on " + rows.getString(5) + "; cannot apply "
                            + plain(allocation.amount()));
                }
            }
        }
        if (!partyOfDocument.equals(partyOf(connection, sourceType, sourceId))) {
            throw new TradeException("Money can only be applied to the same party's documents");
        }
        LocalDate effective = documentDate.isAfter(sourceDate) ? documentDate : sourceDate;
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + allocations
                + " (id, source_type, source_id, document_id, amount_e4, effective_date, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, Ids.newIdString());
            ps.setString(2, sourceType.name());
            ps.setString(3, sourceId);
            ps.setString(4, allocation.documentId());
            ps.setLong(5, money(allocation.amount()));
            ps.setString(6, effective.toString());
            ps.setLong(7, now());
            ps.executeUpdate();
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("holds")) {
                throw new TradeException("This would apply more than the " + noun() + " or note holds", e);
            }
            if (String.valueOf(e.getMessage()).contains("UNIQUE")) {
                throw new TradeException("This is already applied to that document; remove it to change the amount", e);
            }
            throw e;
        }
    }

    /** Package-visible for the document engine's note handling. */
    static void removeAllocationsOfSource(Connection connection, String allocationsTable,
                                          Allocation.SourceType sourceType, String sourceId, LocalDate on,
                                          String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE " + allocationsTable
                + " SET removed_date = MAX(effective_date, ?), removal_reason = ? "
                + "WHERE source_type = ? AND source_id = ? AND removed_date IS NULL")) {
            ps.setString(1, on.toString());
            ps.setString(2, reason);
            ps.setString(3, sourceType.name());
            ps.setString(4, sourceId);
            ps.executeUpdate();
        }
    }

    private void removeAllocationsOfSource(Connection connection, Allocation.SourceType sourceType, String sourceId,
                                           LocalDate on, String reason) throws SQLException {
        removeAllocationsOfSource(connection, allocations, sourceType, sourceId, on, reason);
    }

    private LocalDate sourceDate(Connection connection, Allocation.SourceType sourceType, String sourceId)
            throws SQLException {
        String sql = sourceType == Allocation.SourceType.SETTLEMENT
                ? "SELECT settlement_date FROM " + settlements + " WHERE id = ? AND status = 'POSTED'"
                : "SELECT document_date FROM " + documents
                        + " WHERE id = ? AND status = 'POSTED' AND original_document_id IS NOT NULL";
        return LocalDate.parse(Lookups.single(connection, sql, sourceId,
                "Only a posted " + noun() + " or note can be applied"));
    }

    private String partyOf(Connection connection, Allocation.SourceType sourceType, String sourceId)
            throws SQLException {
        String table = sourceType == Allocation.SourceType.SETTLEMENT ? settlements : documents;
        return Lookups.single(connection, "SELECT party_id FROM " + table + " WHERE id = ?", sourceId,
                "No such " + noun() + " or note: " + sourceId);
    }

    private void requireParty(Connection connection, String partyId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT l.name, p.role, l.is_active FROM party p "
                + "JOIN ledger l ON l.id = p.ledger_id WHERE p.id = ?")) {
            ps.setString(1, partyId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No such party: " + partyId);
                }
                String role = rows.getString(2);
                if (receipts() ? "VENDOR".equals(role) : "CUSTOMER".equals(role)) {
                    throw new TradeException("\"" + rows.getString(1) + "\" is " + (receipts()
                            ? "a vendor only; money received from a vendor is not a sales receipt"
                            : "a customer only; money paid to a customer is not a purchase payment"));
                }
                if (rows.getInt(3) == 0) {
                    throw new TradeException("\"" + rows.getString(1) + "\" is inactive");
                }
            }
        }
    }

    /**
     * P7-16: money moves through a ledger in the Cash &amp; Bank group or any of
     * its sub-groups - Cash in Hand, or a bank account the customer created in
     * the chart - until banking (P10) has its own accounts.
     */
    private static void requireCashOrBank(Connection connection, String ledgerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                WITH RECURSIVE cash_groups(id) AS (
                    SELECT id FROM ledger_group WHERE system_code = 'CASH_AND_BANK'
                    UNION ALL
                    SELECT g.id FROM ledger_group g JOIN cash_groups c ON g.parent_id = c.id
                )
                SELECT EXISTS (SELECT 1 FROM ledger WHERE id = ? AND group_id IN (SELECT id FROM cash_groups))
                """)) {
            ps.setString(1, ledgerId);
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    throw new TradeException("Money is received or paid through a cash or bank account "
                            + "(the Cash & Bank group)");
                }
            }
        }
    }

    private List<Settlement> settlementsWhere(Connection connection, String where, String... parameters)
            throws SQLException {
        List<Settlement> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT s.id, s.number, s.party_id, s.settlement_date, "
                + "s.amount_e4, s.account_ledger_id, s.mode, s.reference_no, s.status, s.voucher_id, "
                + "s.amount_e4 - COALESCE((SELECT SUM(a.amount_e4) FROM " + allocations + " a "
                + "WHERE a.source_type = 'SETTLEMENT' AND a.source_id = s.id AND a.removed_date IS NULL), 0), "
                + "s.tds_e4, s.tds_section FROM " + settlements + " s" + where)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    boolean cancelled = "CANCELLED".equals(rows.getString(9));
                    result.add(new Settlement(rows.getString(1), rows.getString(2), rows.getString(3),
                            LocalDate.parse(rows.getString(4)), MoneyMapper.fromStored(rows.getLong(5), Scale.MONEY),
                            rows.getString(6), PaymentMode.valueOf(rows.getString(7)), rows.getString(8), cancelled,
                            rows.getString(10),
                            cancelled ? BigDecimal.ZERO.setScale(2) : MoneyMapper.fromStored(rows.getLong(11), Scale.MONEY)
                                    .setScale(2), MoneyMapper.fromStored(rows.getLong(12), Scale.MONEY),
                            rows.getString(13)));
                }
            }
        }
        return result;
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

    private static String plain(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
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
            throw new TradeException("Could not read settlements: " + e.getMessage(), e);
        }
    }
}
