package com.accubook.trade;

import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.ReversalRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
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
 * A party's opening position, bill by bill (S2-4, P11-1). Each bill posts on
 * the first day of the books: a customer's Dr party / Cr Opening Balance
 * Equity, a vendor's the reverse. It keeps its own date and due date, so it
 * ages and receives payments like any invoice or bill - and, being in its own
 * table, is never counted as a supply by a GST return or register.
 */
public final class OpeningBills {

    private final DocumentKind.Side side;
    private final ModuleId module;
    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final TransactionalPosting posting;
    private final Clock clock;
    private final String bills;
    private final String allocations;

    public OpeningBills(DocumentKind.Side side, ModuleId module, ModuleTransactions transactions,
                        EntitlementGuard entitlement, TransactionalPosting posting, Clock clock) {
        this.side = Objects.requireNonNull(side, "side");
        this.module = Objects.requireNonNull(module, "module");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.posting = Objects.requireNonNull(posting, "posting");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.bills = side.tablePrefix() + "_opening_bill";
        this.allocations = side.tablePrefix() + "_opening_allocation";
    }

    private boolean sales() {
        return side == DocumentKind.Side.SALES;
    }

    public OpeningBill create(OpeningBillRequest request) {
        Objects.requireNonNull(request, "request");
        entitlement.requireWritable(module, "enter an opening bill");
        String id = Ids.newIdString();
        write("enter the opening bill", c -> {
            Lookups.FiscalYearRow first = firstYear(c);
            if (!request.billDate().isBefore(first.start())) {
                throw new TradeException("An opening bill is dated before the books begin on " + first.start()
                        + "; a later bill is entered as an invoice or bill");
            }
            requireParty(c, request.partyId());
            String party = Lookups.partyLedger(c, request.partyId());
            String equity = Lookups.ledgerByCode(c, "OPENING_BALANCE_EQUITY");
            BigDecimal amount = request.amount();
            List<EntryLine> entries = sales()
                    ? List.of(EntryLine.debit(party, amount), EntryLine.credit(equity, amount))
                    : List.of(EntryLine.debit(equity, amount), EntryLine.credit(party, amount));
            String voucher = posting.postWithin(c, new PostingRequest(module, first.id(),
                    Lookups.voucherType(c, "JOURNAL"), first.start(), null,
                    "Opening " + (sales() ? "invoice " : "bill ") + request.number(), entries,
                    "opening-bill:" + id)).voucherId();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + bills + " (id, party_id, number, bill_date, "
                    + "due_date, amount_e4, fiscal_year_id, voucher_id, narration, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, request.partyId());
                ps.setString(3, request.number());
                ps.setString(4, request.billDate().toString());
                ps.setString(5, request.dueDate() == null ? null : request.dueDate().toString());
                ps.setLong(6, MoneyMapper.toStored(amount.stripTrailingZeros(), Scale.MONEY));
                ps.setString(7, first.id());
                ps.setString(8, voucher);
                ps.setString(9, request.narration() == null || request.narration().isBlank() ? null
                        : request.narration().strip());
                ps.setLong(10, clock.millis());
                ps.executeUpdate();
            } catch (SQLException e) {
                if (String.valueOf(e.getMessage()).contains("UNIQUE")) {
                    throw new TradeException("This party already has an opening bill numbered " + request.number(), e);
                }
                throw e;
            }
        });
        return find(id).orElseThrow();
    }

    /**
     * Cancel an opening bill entered by mistake: its voucher is reversed on the
     * first day. Refused while payments are applied to it, and once the first
     * year is closed.
     */
    public OpeningBill cancel(String billId, String reason) {
        entitlement.requireWritable(module, "cancel an opening bill");
        if (reason == null || reason.isBlank()) {
            throw new TradeException("A cancellation needs a reason; it stays on the record");
        }
        write("cancel the opening bill", c -> {
            String voucher;
            String number;
            try (PreparedStatement ps = c.prepareStatement("SELECT status, voucher_id, number FROM " + bills
                    + " WHERE id = ?")) {
                ps.setString(1, billId);
                try (ResultSet r = ps.executeQuery()) {
                    if (!r.next()) {
                        throw new TradeException("No such opening bill: " + billId);
                    }
                    if (!"POSTED".equals(r.getString(1))) {
                        throw new TradeException("This opening bill is already cancelled");
                    }
                    voucher = r.getString(2);
                    number = r.getString(3);
                }
            }
            if (live(c, billId) > 0) {
                throw new TradeException("Opening bill " + number + " has payments applied to it; remove them first");
            }
            Lookups.FiscalYearRow first = firstYear(c);
            String reversal = posting.reverseWithin(c, voucher, new ReversalRequest(module, first.id(), first.start(),
                    "Opening bill " + number + " cancelled: " + reason.strip(), null,
                    "opening-bill-cancel:" + billId)).voucherId();
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + bills + " SET status = 'CANCELLED', "
                    + "cancellation_voucher_id = ?, cancellation_reason = ? WHERE id = ?")) {
                ps.setString(1, reversal);
                ps.setString(2, reason.strip());
                ps.setString(3, billId);
                ps.executeUpdate();
            }
        });
        return find(billId).orElseThrow();
    }

    public Optional<OpeningBill> find(String billId) {
        List<OpeningBill> found = where(" WHERE b.id = ?", billId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Every opening bill, oldest first. */
    public List<OpeningBill> list() {
        return where(" ORDER BY b.bill_date, b.number");
    }

    private List<OpeningBill> where(String clause, String... parameters) {
        return read(c -> {
            List<OpeningBill> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT b.id, b.party_id, b.number, b.bill_date, b.due_date, "
                    + "b.amount_e4, b.amount_e4 - COALESCE((SELECT SUM(a.amount_e4) FROM " + allocations + " a "
                    + "WHERE a.opening_bill_id = b.id AND a.removed_date IS NULL), 0), b.status, b.voucher_id FROM "
                    + bills + " b" + clause)) {
                for (int i = 0; i < parameters.length; i++) {
                    ps.setString(i + 1, parameters[i]);
                }
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        boolean cancelled = "CANCELLED".equals(r.getString(8));
                        String due = r.getString(5);
                        result.add(new OpeningBill(r.getString(1), r.getString(2), r.getString(3),
                                LocalDate.parse(r.getString(4)), due == null ? null : LocalDate.parse(due),
                                money(r.getLong(6)), cancelled ? money(0) : money(r.getLong(7)), cancelled,
                                r.getString(9)));
                    }
                }
            }
            return result;
        });
    }

    private long live(Connection c, String billId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + allocations
                + " WHERE opening_bill_id = ? AND removed_date IS NULL")) {
            ps.setString(1, billId);
            try (ResultSet r = ps.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    /** The first fiscal year, which must still be open: opening bills belong to it. */
    private static Lookups.FiscalYearRow firstYear(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id, start_date, end_date, is_closed FROM fiscal_year "
                + "ORDER BY start_date LIMIT 1"); ResultSet r = ps.executeQuery()) {
            if (!r.next()) {
                throw new TradeException("This company has no fiscal year");
            }
            if (r.getInt(4) == 1) {
                throw new TradeException("The first year is closed; opening bills can no longer change");
            }
            return new Lookups.FiscalYearRow(r.getString(1), LocalDate.parse(r.getString(2)),
                    LocalDate.parse(r.getString(3)));
        }
    }

    private void requireParty(Connection c, String partyId) throws SQLException {
        String role = Lookups.single(c, "SELECT role FROM party WHERE id = ?", partyId, "No such party: " + partyId);
        if (sales() ? "VENDOR".equals(role) : "CUSTOMER".equals(role)) {
            throw new TradeException(sales() ? "A vendor's opening bills are entered on the purchase side"
                    : "A customer's opening invoices are entered on the sales side");
        }
    }

    private static BigDecimal money(long e4) {
        return MoneyMapper.fromStored(e4, Scale.MONEY).setScale(2);
    }

    @FunctionalInterface
    private interface Write {
        void run(Connection connection) throws SQLException;
    }

    private void write(String description, Write work) {
        try {
            transactions.inTransaction(c -> {
                work.run(c);
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
            throw new TradeException("Could not read opening bills: " + e.getMessage(), e);
        }
    }
}
