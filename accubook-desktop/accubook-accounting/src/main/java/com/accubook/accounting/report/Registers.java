package com.accubook.accounting.report;

import com.accubook.module.ModuleTransactions;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The books as lists (Phase 11c): the day book, a ledger account with its
 * running balance, and the sales and purchase registers. Each is a
 * {@link Table}, so any of them exports to CSV, JSON or XLSX.
 */
public final class Registers {

    private static final Table.Column DATE = new Table.Column("Date", Table.Kind.DATE);

    private final ModuleTransactions transactions;

    public Registers(ModuleTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Every posted voucher between two dates, one row per entry. */
    public Table dayBook(LocalDate from, LocalDate to) {
        return read(c -> {
            List<List<Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT v.voucher_date, t.name, v.voucher_number, COALESCE(v.narration, ''), l.name,
                           e.debit_amount_e4, e.credit_amount_e4
                    FROM voucher v JOIN voucher_type t ON t.id = v.voucher_type_id
                    JOIN voucher_entry e ON e.voucher_id = v.id JOIN ledger l ON l.id = e.ledger_id
                    WHERE v.is_posted = 1 AND v.voucher_date BETWEEN ? AND ?
                    ORDER BY v.voucher_date, v.posted_at, v.rowid, e.sequence, e.rowid
                    """)) {
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        rows.add(Arrays.asList(LocalDate.parse(r.getString(1)), r.getString(2), r.getString(3),
                                r.getString(4), r.getString(5), nullIfZero(r.getLong(6)), nullIfZero(r.getLong(7))));
                    }
                }
            }
            return new Table("Day book " + from + " to " + to, List.of(DATE, text("Type"), text("Number"),
                    text("Narration"), text("Ledger"), money("Debit"), money("Credit")), rows);
        });
    }

    /**
     * A ledger account between two dates: the balance brought forward, each
     * entry, and the running balance - debit positive, credit negative.
     */
    public Table ledgerAccount(String ledgerId, LocalDate from, LocalDate to) {
        return read(c -> {
            String name = single(c, "SELECT name FROM ledger WHERE id = ?", ledgerId);
            BigDecimal balance;
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(e.debit_amount_e4 - "
                    + "e.credit_amount_e4), 0) FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id "
                    + "WHERE e.ledger_id = ? AND v.is_posted = 1 AND v.voucher_date < ?")) {
                ps.setString(1, ledgerId);
                ps.setString(2, from.toString());
                try (ResultSet r = ps.executeQuery()) {
                    r.next();
                    balance = amount(r.getLong(1));
                }
            }
            List<List<Object>> rows = new ArrayList<>();
            rows.add(Arrays.asList(from, "", "", "Balance brought forward", null, null, balance));
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT v.voucher_date, t.name, v.voucher_number, COALESCE(e.narration, v.narration, ''),
                           e.debit_amount_e4, e.credit_amount_e4
                    FROM voucher_entry e JOIN voucher v ON v.id = e.voucher_id
                    JOIN voucher_type t ON t.id = v.voucher_type_id
                    WHERE e.ledger_id = ? AND v.is_posted = 1 AND v.voucher_date BETWEEN ? AND ?
                    ORDER BY v.voucher_date, v.posted_at, v.rowid, e.sequence, e.rowid
                    """)) {
                ps.setString(1, ledgerId);
                ps.setString(2, from.toString());
                ps.setString(3, to.toString());
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        balance = balance.add(amount(r.getLong(5))).subtract(amount(r.getLong(6)));
                        rows.add(Arrays.asList(LocalDate.parse(r.getString(1)), r.getString(2),
                                r.getString(3), r.getString(4), nullIfZero(r.getLong(5)),
                                nullIfZero(r.getLong(6)), balance));
                    }
                }
            }
            return new Table("Ledger: " + name + " " + from + " to " + to, List.of(DATE, text("Type"),
                    text("Number"), text("Narration"), money("Debit"), money("Credit"), money("Balance")), rows);
        });
    }

    /** Posted and cancelled invoices and credit notes between two dates; notes as negatives. */
    public Table salesRegister(LocalDate from, LocalDate to) {
        return register("sales", "Sales register", from, to);
    }

    /** Posted and cancelled bills and debit notes between two dates; notes as negatives. */
    public Table purchaseRegister(LocalDate from, LocalDate to) {
        return register("purchase", "Purchase register", from, to);
    }

    private Table register(String side, String title, LocalDate from, LocalDate to) {
        return read(c -> {
            List<List<Object>> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT d.document_date, d.number, d.kind, p.name, COALESCE(d.party_gstin, ''),
                           d.taxable_e4, d.tax_e4, d.total_e4, d.status, d.original_document_id IS NOT NULL
                    FROM %1$s_document d JOIN party p ON p.id = d.party_id
                    WHERE d.status IN ('POSTED', 'CANCELLED') AND d.document_date BETWEEN ? AND ?
                    ORDER BY d.document_date, d.number
                    """.formatted(side))) {
                ps.setString(1, from.toString());
                ps.setString(2, to.toString());
                try (ResultSet r = ps.executeQuery()) {
                    while (r.next()) {
                        BigDecimal sign = r.getInt(10) == 1 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
                        rows.add(List.of(LocalDate.parse(r.getString(1)), r.getString(2), kind(r.getString(3)),
                                r.getString(4), r.getString(5), amount(r.getLong(6)).multiply(sign),
                                amount(r.getLong(7)).multiply(sign), amount(r.getLong(8)).multiply(sign),
                                "CANCELLED".equals(r.getString(9)) ? "Cancelled" : "Posted"));
                    }
                }
            }
            return new Table(title + " " + from + " to " + to, List.of(DATE, text("Number"), text("Kind"),
                    text("Party"), text("GSTIN"), money("Taxable"), money("Tax"), money("Total"), text("Status")),
                    rows);
        });
    }

    private static String kind(String kind) {
        return switch (kind) {
            case "SALES_INVOICE" -> "Invoice";
            case "CREDIT_NOTE" -> "Credit note";
            case "PURCHASE_BILL" -> "Bill";
            case "DEBIT_NOTE" -> "Debit note";
            default -> kind;
        };
    }

    private static Table.Column text(String name) {
        return new Table.Column(name, Table.Kind.TEXT);
    }

    private static Table.Column money(String name) {
        return new Table.Column(name, Table.Kind.MONEY);
    }

    private static BigDecimal nullIfZero(long e4) {
        return e4 == 0 ? null : amount(e4);
    }

    private static BigDecimal amount(long e4) {
        return MoneyMapper.fromStored(e4, Scale.MONEY).setScale(2);
    }

    private static String single(Connection c, String sql, String parameter) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalArgumentException("Not found: " + parameter);
                }
                return r.getString(1);
            }
        }
    }

    private <T> T read(ModuleTransactions.Work<T> work) {
        try {
            return transactions.read(work);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the books: " + e.getMessage(), e);
        }
    }
}
