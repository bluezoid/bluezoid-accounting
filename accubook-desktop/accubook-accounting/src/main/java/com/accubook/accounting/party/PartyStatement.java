package com.accubook.accounting.party;

import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A party's statement of account between two dates.
 *
 * <p>Read from the journal - the party's ledger entries on posted vouchers -
 * not from the documents. It therefore agrees with the trial balance by
 * construction: the closing balance <em>is</em> the ledger balance. A reversed
 * voucher and its reversal both appear, and net to nothing, which is what an
 * auditor expects to see.
 *
 * <p>Balances are signed debit-positive: for a customer, a positive balance is
 * money owed to the business; for a vendor, a negative balance is money the
 * business owes.
 *
 * @param opening movement on the ledger before {@code from}
 */
public record PartyStatement(String partyId, LocalDate from, LocalDate to, BigDecimal opening,
                             List<Line> lines, BigDecimal closing) {

    public record Line(LocalDate date, String voucherNumber, String referenceNo, String narration,
                       BigDecimal debit, BigDecimal credit, BigDecimal balance) {
    }

    public PartyStatement {
        lines = List.copyOf(lines);
    }

    /**
     * Opening balances entered on the ledger itself are not included yet: they
     * are applied by year-end handling in P11, consistently with the trial
     * balance.
     */
    public static PartyStatement compute(Connection connection, String partyId, LocalDate from, LocalDate to)
            throws SQLException {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new PartyException("A statement's end date cannot be before its start");
        }
        String ledgerId;
        try (PreparedStatement ps = connection.prepareStatement("SELECT ledger_id FROM party WHERE id = ?")) {
            ps.setString(1, partyId);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new PartyException("No such party: " + partyId);
                }
                ledgerId = rows.getString(1);
            }
        }

        long openingE4;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COALESCE(SUM(e.debit_amount_e4 - e.credit_amount_e4), 0) FROM voucher_entry e "
                        + "JOIN voucher v ON v.id = e.voucher_id "
                        + "WHERE e.ledger_id = ? AND v.is_posted = 1 AND v.voucher_date < ?")) {
            ps.setString(1, ledgerId);
            ps.setString(2, from.toString());
            try (ResultSet rows = ps.executeQuery()) {
                rows.next();
                openingE4 = rows.getLong(1);
            }
        }

        List<Line> lines = new ArrayList<>();
        long running = openingE4;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT v.voucher_date, v.voucher_number, v.reference_no, COALESCE(e.narration, v.narration), "
                        + "e.debit_amount_e4, e.credit_amount_e4 FROM voucher_entry e "
                        + "JOIN voucher v ON v.id = e.voucher_id "
                        + "WHERE e.ledger_id = ? AND v.is_posted = 1 AND v.voucher_date BETWEEN ? AND ? "
                        + "ORDER BY v.voucher_date, v.posted_at, v.id, e.id")) {
            ps.setString(1, ledgerId);
            ps.setString(2, from.toString());
            ps.setString(3, to.toString());
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    long debit = rows.getLong(5);
                    long credit = rows.getLong(6);
                    running += debit - credit;
                    lines.add(new Line(LocalDate.parse(rows.getString(1)), rows.getString(2), rows.getString(3),
                            rows.getString(4), money(debit), money(credit), money(running)));
                }
            }
        }
        return new PartyStatement(partyId, from, to, money(openingE4), lines, money(running));
    }

    private static BigDecimal money(long e4) {
        return MoneyMapper.fromStored(e4, Scale.MONEY);
    }
}
