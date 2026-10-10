package com.accubook.trade;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/** Reads and the number series shared by the document and settlement engines. */
final class Lookups {

    private Lookups() {
    }

    record FiscalYearRow(String id, LocalDate start, LocalDate end) {
    }

    /**
     * The fiscal year a date falls in, read from the company file - never from a
     * calendar rule (R-9, D-32). A closed year refuses new entries.
     */
    static FiscalYearRow fiscalYearFor(Connection connection, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, start_date, end_date, is_closed "
                + "FROM fiscal_year WHERE start_date <= ? AND end_date >= ?")) {
            ps.setString(1, date.toString());
            ps.setString(2, date.toString());
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException("No fiscal year covers " + date + ". Create that year first.");
                }
                if (rows.getInt(4) == 1) {
                    throw new TradeException("The fiscal year containing " + date + " is closed");
                }
                return new FiscalYearRow(rows.getString(1), LocalDate.parse(rows.getString(2)),
                        LocalDate.parse(rows.getString(3)));
            }
        }
    }

    static String ledgerByCode(Connection connection, String code) throws SQLException {
        return single(connection, "SELECT id FROM ledger WHERE system_code = ?", code,
                "The " + code + " account is missing from this company's chart of accounts");
    }

    static String voucherType(Connection connection, String code) throws SQLException {
        return single(connection, "SELECT id FROM voucher_type WHERE code = ?", code,
                "The " + code + " voucher type is missing from this company");
    }

    static String partyLedger(Connection connection, String partyId) throws SQLException {
        return single(connection, "SELECT ledger_id FROM party WHERE id = ?", partyId, "No such party: " + partyId);
    }

    static String single(Connection connection, String sql, String parameter, String missing) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, parameter);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new TradeException(missing);
                }
                return rows.getString(1);
            }
        }
    }

    /** Same pattern as the posting core's allocator: inside BEGIN IMMEDIATE, so numbers queue, never race. */
    static long nextSequence(Connection connection, String scope, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO number_counter (scope, last_number, "
                + "updated_at) VALUES (?, 0, ?) ON CONFLICT (scope) DO NOTHING")) {
            ps.setString(1, scope);
            ps.setLong(2, now);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE number_counter SET last_number = last_number + 1, updated_at = ? WHERE scope = ?")) {
            ps.setLong(1, now);
            ps.setString(2, scope);
            ps.executeUpdate();
        }
        return Long.parseLong(single(connection, "SELECT last_number FROM number_counter WHERE scope = ?", scope,
                "Number series vanished: " + scope));
    }
}
