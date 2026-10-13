package com.accubook.tax;

import com.accubook.module.ModuleTransactions;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The company's Tax deduction Account Number - four letters, five digits, a
 * letter - which every TDS deposit and return quotes. Kept in the company
 * file's own identity ({@code company_info}). Kernel: deducting TDS needs it in
 * every plan.
 */
public final class Tan {

    private static final String FORMAT = "[A-Z]{4}\\d{5}[A-Z]";

    private final ModuleTransactions transactions;

    public Tan(ModuleTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    public static Optional<String> problem(String tan) {
        if (tan == null || !tan.strip().toUpperCase(Locale.ROOT).matches(FORMAT)) {
            return Optional.of("\"" + tan + "\" is not a TAN: four letters, five digits, a letter");
        }
        return Optional.empty();
    }

    public void set(String tan) {
        problem(tan).ifPresent(p -> {
            throw new RulePackException(p);
        });
        try {
            transactions.inTransaction(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE company_info SET tan_no = ?, updated_at = ?")) {
                    ps.setString(1, tan.strip().toUpperCase(Locale.ROOT));
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not record the TAN: " + e.getMessage(), e);
        }
    }

    public Optional<String> get() {
        try {
            return transactions.read(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("SELECT tan_no FROM company_info");
                     ResultSet rows = ps.executeQuery()) {
                    return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.<String>empty();
                }
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not read the TAN: " + e.getMessage(), e);
        }
    }
}
