package com.accubook.tax;

import com.accubook.module.ModuleTransactions;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * The company's legal name and address, as e-invoices and e-way bills state the
 * seller (P9e). Kept in the company file's own identity ({@code company_info}).
 */
public final class CompanyIdentity {

    /** @param stateCode two digits, from the rule pack; @param pin six digits */
    public record Address(String legalName, String line1, String city, String stateCode, String pin) {
    }

    private final ModuleTransactions transactions;

    public CompanyIdentity(ModuleTransactions transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Why {@code pin} is not an Indian PIN code, or empty if it is one. */
    public static Optional<String> pinProblem(String pin) {
        return pin != null && pin.strip().matches("[1-9]\\d{5}") ? Optional.empty()
                : Optional.of("\"" + pin + "\" is not a PIN code: six digits, not starting with 0");
    }

    public void set(Address address, RulePack rules) {
        Objects.requireNonNull(address, "address");
        if (address.legalName() == null || address.legalName().isBlank()
                || address.line1() == null || address.line1().isBlank()
                || address.city() == null || address.city().isBlank()) {
            throw new RulePackException("A legal name, an address line and a city are required");
        }
        if (address.stateCode() == null || rules.state(address.stateCode()).isEmpty()) {
            throw new RulePackException("\"" + address.stateCode() + "\" is not a state code in rule pack "
                    + rules.version());
        }
        pinProblem(address.pin()).ifPresent(p -> {
            throw new RulePackException(p);
        });
        try {
            transactions.inTransaction(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("UPDATE company_info SET legal_name = ?, "
                        + "address_line1 = ?, address_city = ?, address_state_code = ?, address_postal_code = ?, "
                        + "updated_at = ?")) {
                    ps.setString(1, address.legalName().strip());
                    ps.setString(2, address.line1().strip());
                    ps.setString(3, address.city().strip());
                    ps.setString(4, address.stateCode());
                    ps.setString(5, address.pin().strip());
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not record the company's address: " + e.getMessage(), e);
        }
    }

    /** The address, or empty until one is recorded. */
    public Optional<Address> get() {
        try {
            return transactions.read(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("SELECT legal_name, address_line1, "
                        + "address_city, address_state_code, address_postal_code FROM company_info");
                     ResultSet r = ps.executeQuery()) {
                    if (!r.next() || r.getString(2) == null) {
                        return Optional.<Address>empty();
                    }
                    return Optional.of(new Address(r.getString(1), r.getString(2), r.getString(3), r.getString(4),
                            r.getString(5)));
                }
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not read the company's address: " + e.getMessage(), e);
        }
    }
}
