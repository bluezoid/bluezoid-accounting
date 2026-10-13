package com.accubook.returns;

import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.TransactionalPosting;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TDS and TCS deposits, and the quarterly 26Q / 27EQ and Form 16A data (P9d-5).
 *
 * <p>A deposit pays the deductions of one section (194C-IND and 194C-OTH are
 * both 194C on the challan) and is posted on entry: Dr TDS Payable (or TCS
 * Payable) for the tax, Dr Interest and Fees on TDS for interest and fee, Cr the
 * bank. s.201(1A) interest is computed per deduction (P9-23); paying less than
 * it is recorded as entered and flagged in 26Q, not refused.
 */
public final class TdsDeposits {

    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final TransactionalPosting posting;
    private final Clock clock;

    public TdsDeposits(ModuleTransactions transactions, EntitlementGuard entitlement, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.posting = new TransactionalPosting(entitlement, clock);
    }

    /** Every TDS ({@code "TDS"}) or TCS deduction dated between the two dates, net of notes. */
    public List<TdsDeduction> deductions(String kind, LocalDate from, LocalDate to) {
        requireReadable();
        try {
            return transactions.read(connection -> deductions(connection, kind, from, to));
        } catch (SQLException e) {
            throw new ReturnException("Could not read deductions: " + e.getMessage(), e);
        }
    }

    /** Deposit deductions; posted at once, and never edited. */
    public Challan deposit(ChallanRequest request) {
        entitlement.requireWritable(TaxationReturnsModule.ID, "deposit TDS");
        String id = Ids.newIdString();
        try {
            transactions.inTransaction(connection -> {
                List<TdsDeduction> chosen = new ArrayList<>();
                for (String entryId : request.entryIds()) {
                    TdsDeduction d = deduction(connection, entryId);
                    if (d.challanId() != null) {
                        throw new ReturnException(d.documentNumber() + "'s " + d.kind() + " is already deposited");
                    }
                    if (d.amount().signum() <= 0) {
                        throw new ReturnException(d.documentNumber() + " has nothing left to deposit");
                    }
                    if (request.depositDate().isBefore(d.date())) {
                        throw new ReturnException("A deposit cannot precede the deduction on " + d.date());
                    }
                    chosen.add(d);
                }
                String section = chosen.get(0).challanSection();
                String kind = chosen.get(0).kind();
                if (chosen.stream().anyMatch(d -> !d.challanSection().equals(section) || !d.kind().equals(kind))) {
                    throw new ReturnException("A challan deposits one section's " + kind + "; split by section");
                }
                BigDecimal tax = chosen.stream().map(TdsDeduction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                requireCashOrBank(connection, request.accountLedgerId());
                List<EntryLine> entries = new ArrayList<>();
                entries.add(EntryLine.debit(ledger(connection, "TDS".equals(kind) ? "TDS_PAYABLE" : "TCS_PAYABLE"),
                        tax));
                BigDecimal charges = request.interest().add(request.fee());
                if (charges.signum() > 0) {
                    entries.add(EntryLine.debit(ledger(connection, "TDS_INTEREST"), charges));
                }
                entries.add(EntryLine.credit(request.accountLedgerId(), tax.add(charges)));
                PostedVoucher voucher = posting.postWithin(connection, new PostingRequest(TaxationReturnsModule.ID,
                        fiscalYear(connection, request.depositDate()), single(connection,
                        "SELECT id FROM voucher_type WHERE code = 'PAYMENT'"), request.depositDate(),
                        request.bsrCode() + "/" + request.challanSerial(), kind + " " + section + " deposited, BSR "
                        + request.bsrCode() + " challan " + request.challanSerial(), entries, "challan:" + id));
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO tds_challan (id, kind, section, "
                        + "deposit_date, bsr_code, challan_serial, tax_e4, interest_e4, fee_e4, account_ledger_id, "
                        + "voucher_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, kind);
                    ps.setString(3, section);
                    ps.setString(4, request.depositDate().toString());
                    ps.setString(5, request.bsrCode());
                    ps.setString(6, request.challanSerial());
                    ps.setLong(7, money(tax));
                    ps.setLong(8, money(request.interest()));
                    ps.setLong(9, money(request.fee()));
                    ps.setString(10, request.accountLedgerId());
                    ps.setString(11, voucher.voucherId());
                    ps.setLong(12, clock.millis());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO tds_challan_entry (challan_id, "
                        + "withholding_entry_id, amount_e4) VALUES (?, ?, ?)")) {
                    for (TdsDeduction d : chosen) {
                        ps.setString(1, id);
                        ps.setString(2, d.entryId());
                        ps.setLong(3, money(d.amount()));
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw new ReturnException(e.getMessage() != null && e.getMessage().contains("UNIQUE")
                    ? "A challan with that BSR code, date and serial is already recorded"
                    : "Could not record the deposit: " + e.getMessage(), e);
        }
        return challans().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    public List<Challan> challans() {
        requireReadable();
        try {
            return transactions.read(connection -> challans(connection, null, null));
        } catch (SQLException e) {
            throw new ReturnException("Could not read deposits: " + e.getMessage(), e);
        }
    }

    /**
     * A quarter's 26Q (TDS) or 27EQ (TCS) data for the RPU (P9-24). Blocking:
     * no TAN, or a deduction not yet deposited. Warnings: interest paid short of
     * s.201(1A), and deductees without a PAN.
     */
    public Form26Q quarterly(String kind, ReturnPeriod quarter) {
        requireReadable();
        if (!quarter.isQuarter()) {
            throw new ReturnException("26Q and 27EQ are quarterly");
        }
        try {
            return transactions.read(connection -> {
                List<Gstr1.Issue> issues = new ArrayList<>();
                String[] deductor = deductor(connection);
                if (deductor[0] == null) {
                    issues.add(new Gstr1.Issue(true, "", "Record the company's TAN; every TDS return quotes it"));
                }
                List<TdsDeduction> rows = deductions(connection, kind, quarter.from(), quarter.to());
                List<Challan> challans = challans(connection, quarter.from(), quarter.to());
                Map<String, BigDecimal> interestDue = new LinkedHashMap<>();
                for (TdsDeduction d : rows) {
                    if (d.amount().signum() <= 0) {
                        continue;
                    }
                    if (d.challanId() == null) {
                        issues.add(new Gstr1.Issue(true, d.documentNumber(), kind + " of " + d.amount().toPlainString()
                                + " deducted on " + d.date() + " is not deposited (due " + d.dueDate() + ")"));
                    } else {
                        interestDue.merge(d.challanId(), d.interestIfDepositedOn(d.depositDate()), BigDecimal::add);
                    }
                    if (d.pan() == null) {
                        issues.add(new Gstr1.Issue(false, d.documentNumber(), d.partyName() + " has no PAN; "
                                + "deducted at the no-PAN rate"));
                    }
                }
                for (Challan c : challans) {
                    BigDecimal due = interestDue.getOrDefault(c.id(), BigDecimal.ZERO);
                    if (c.interest().compareTo(due) < 0) {
                        issues.add(new Gstr1.Issue(false, c.bsrCode() + "/" + c.challanSerial(), "Deposited late: "
                                + "s.201(1A) interest is " + due.toPlainString() + ", paid " + c.interest()
                                .toPlainString()));
                    }
                }
                return new Form26Q(quarter, kind, deductor[0], deductor[1], rows, challans, issues);
            });
        } catch (SQLException e) {
            throw new ReturnException("Could not build the quarterly TDS data: " + e.getMessage(), e);
        }
    }

    /** Each deductee's Form 16A figures for the quarter. */
    public List<Form16A> form16A(ReturnPeriod quarter) {
        Form26Q q = quarterly("TDS", quarter);
        Map<String, List<TdsDeduction>> byParty = new LinkedHashMap<>();
        for (TdsDeduction d : q.deductions()) {
            if (d.amount().signum() > 0) {
                byParty.computeIfAbsent(d.partyId(), k -> new ArrayList<>()).add(d);
            }
        }
        List<Form16A> result = new ArrayList<>();
        byParty.values().forEach(list -> result.add(new Form16A(quarter, q.tan(), q.deductorName(),
                list.get(0).partyName(), list.get(0).pan(), list)));
        return result;
    }

    /** The RPU data as JSON ({@code format: accubook-26q/1}). */
    public String quarterlyJson(String kind, ReturnPeriod quarter) {
        Form26Q q = quarterly(kind, quarter);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "accubook-26q/1");
        root.put("form", "TDS".equals(kind) ? "26Q" : "27EQ");
        root.put("tan", q.tan());
        root.put("deductor", q.deductorName());
        root.put("quarter", quarter.from() + "/" + quarter.to());
        List<Object> challans = new ArrayList<>();
        for (Challan c : q.challans()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", c.section());
            m.put("bsr_code", c.bsrCode());
            m.put("challan_serial", c.challanSerial());
            m.put("deposit_date", c.depositDate().toString());
            m.put("tax", Json.amount(c.tax()));
            m.put("interest", Json.amount(c.interest()));
            m.put("fee", Json.amount(c.fee()));
            challans.add(m);
        }
        root.put("challans", challans);
        List<Object> deductees = new ArrayList<>();
        for (TdsDeduction d : q.deductions()) {
            if (d.amount().signum() <= 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pan", d.pan() == null ? "PANNOTAVBL" : d.pan());
            m.put("name", d.partyName());
            m.put("section", d.section());
            m.put("date_of_credit", d.date().toString());
            m.put("amount_paid", Json.amount(d.base()));
            m.put("rate", d.rate());
            m.put("tax_deducted", Json.amount(d.amount()));
            m.put("date_of_deduction", d.date().toString());
            m.put("challan", d.challanId() == null ? null : d.challanId());
            deductees.add(m);
        }
        root.put("deductees", deductees);
        return Json.write(root);
    }

    // ================================================================ reads

    private List<TdsDeduction> deductions(Connection connection, String kind, LocalDate from, LocalDate to)
            throws SQLException {
        List<TdsDeduction> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(deductionSql(kind)
                + " AND e.entry_date BETWEEN ? AND ? ORDER BY e.entry_date, d.number")) {
            ps.setString(1, kind);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    result.add(deduction(r));
                }
            }
        }
        return result;
    }

    private TdsDeduction deduction(Connection connection, String entryId) throws SQLException {
        String kind;
        try (PreparedStatement ps = connection.prepareStatement("SELECT kind FROM withholding_entry WHERE id = ?")) {
            ps.setString(1, entryId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new ReturnException("No deduction " + entryId + " to deposit");
                }
                kind = r.getString(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(deductionSql(kind) + " AND e.id = ?")) {
            ps.setString(1, kind);
            ps.setString(2, entryId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new ReturnException("No deduction " + entryId + " to deposit");
                }
                return deduction(r);
            }
        }
    }

    /** A document's deduction, net of the notes that reversed part of it. */
    private static String deductionSql(String kind) {
        String documents = "TDS".equals(kind) ? "purchase_document" : "sales_document";
        return """
                SELECT e.id, e.kind, e.section, e.party_id, p.name, e.pan, d.number, e.entry_date,
                       e.base_e4 + COALESCE((SELECT SUM(n.base_e4) FROM withholding_entry n
                           JOIN %1$s nd ON nd.id = n.source_id
                           WHERE nd.original_document_id = e.source_id AND n.status = 'ACTIVE'), 0),
                       e.rate_e4,
                       e.amount_e4 + COALESCE((SELECT SUM(n.amount_e4) FROM withholding_entry n
                           JOIN %1$s nd ON nd.id = n.source_id
                           WHERE nd.original_document_id = e.source_id AND n.status = 'ACTIVE'), 0),
                       c.challan_id, ch.deposit_date
                FROM withholding_entry e
                JOIN %1$s d ON d.id = e.source_id
                JOIN party p ON p.id = e.party_id
                LEFT JOIN tds_challan_entry c ON c.withholding_entry_id = e.id
                LEFT JOIN tds_challan ch ON ch.id = c.challan_id
                WHERE e.kind = ? AND e.direction = 'PAYABLE' AND e.status = 'ACTIVE'
                  AND d.original_document_id IS NULL
                """.formatted(documents);
    }

    private static TdsDeduction deduction(ResultSet r) throws SQLException {
        String deposited = r.getString(13);
        return new TdsDeduction(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getString(7), LocalDate.parse(r.getString(8)),
                MoneyMapper.fromStored(r.getLong(9), Scale.MONEY).setScale(2),
                MoneyMapper.fromStored(r.getLong(10), Scale.TAX_RATE).stripTrailingZeros(),
                MoneyMapper.fromStored(r.getLong(11), Scale.MONEY).setScale(2), r.getString(12),
                deposited == null ? null : LocalDate.parse(deposited));
    }

    private static List<Challan> challans(Connection connection, LocalDate from, LocalDate to) throws SQLException {
        List<Challan> result = new ArrayList<>();
        String where = from == null ? "" : " WHERE id IN (SELECT c.challan_id FROM tds_challan_entry c "
                + "JOIN withholding_entry e ON e.id = c.withholding_entry_id WHERE e.entry_date BETWEEN ? AND ?)";
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, kind, section, deposit_date, bsr_code, "
                + "challan_serial, tax_e4, interest_e4, fee_e4, voucher_id FROM tds_challan" + where
                + " ORDER BY deposit_date, challan_serial")) {
            if (from != null) {
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
            }
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    result.add(new Challan(r.getString(1), r.getString(2), r.getString(3),
                            LocalDate.parse(r.getString(4)), r.getString(5), r.getString(6),
                            MoneyMapper.fromStored(r.getLong(7), Scale.MONEY).setScale(2),
                            MoneyMapper.fromStored(r.getLong(8), Scale.MONEY).setScale(2),
                            MoneyMapper.fromStored(r.getLong(9), Scale.MONEY).setScale(2), r.getString(10)));
                }
            }
        }
        return result;
    }

    private static String[] deductor(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT tan_no, COALESCE(legal_name, name) "
                + "FROM company_info"); ResultSet r = ps.executeQuery()) {
            r.next();
            return new String[]{r.getString(1), r.getString(2)};
        }
    }

    private static void requireCashOrBank(Connection connection, String ledgerId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM ledger l JOIN ledger_group g "
                + "ON g.id = l.group_id WHERE l.id = ? AND g.system_code = 'CASH_AND_BANK'")) {
            ps.setString(1, ledgerId);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new ReturnException("A deposit is paid from a Cash & Bank account");
                }
            }
        }
    }

    private static String ledger(Connection connection, String code) throws SQLException {
        return single(connection, "SELECT id FROM ledger WHERE system_code = '" + code + "'");
    }

    private static String fiscalYear(Connection connection, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, is_closed FROM fiscal_year "
                + "WHERE start_date <= ? AND end_date >= ?")) {
            ps.setString(1, date.toString());
            ps.setString(2, date.toString());
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new ReturnException("No fiscal year covers " + date + ". Create that year first.");
                }
                if (rows.getInt(2) == 1) {
                    throw new ReturnException("The fiscal year containing " + date + " is closed");
                }
                return rows.getString(1);
            }
        }
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql); ResultSet rows = ps.executeQuery()) {
            if (!rows.next()) {
                throw new ReturnException("Not found: " + sql);
            }
            return rows.getString(1);
        }
    }

    private static long money(BigDecimal amount) {
        return MoneyMapper.toStored(amount, Scale.MONEY);
    }

    private void requireReadable() {
        if (!entitlement.isReadable(TaxationReturnsModule.ID)) {
            throw new ReturnException("Tax returns are not part of this licence");
        }
    }
}
