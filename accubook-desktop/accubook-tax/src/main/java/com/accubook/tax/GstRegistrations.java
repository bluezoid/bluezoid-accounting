package com.accubook.tax;

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
 * The company's GST registration history (kernel; never entitlement-gated).
 *
 * <p>Appended forward only: a new registration takes effect after the latest
 * one, so a document already taxed under a registration keeps its basis.
 */
public final class GstRegistrations {

    private final ModuleTransactions transactions;
    private final Clock clock;

    public GstRegistrations(ModuleTransactions transactions, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Record a registration from {@code effectiveFrom}, validated against
     * {@code rules}: a registered scheme needs a valid GSTIN (format, state,
     * check character) whose state is the company's state.
     *
     * <p>Composition is refused when the preceding financial year's turnover
     * exceeds the limit for the state (P9b). The turnover is what this
     * company file knows: posted sales invoices less credit notes, at taxable
     * value. The law counts aggregate turnover across every registration under
     * the PAN, which a single company file cannot see.
     */
    public GstRegistration register(GstRegistration.Scheme scheme, String gstin, String stateCode,
                                    LocalDate effectiveFrom, RulePack rules) {
        return register(scheme, gstin, stateCode, effectiveFrom, rules, null);
    }

    /** @param category required for composition, which it decides the rate of (P9-20); refused otherwise */
    public GstRegistration register(GstRegistration.Scheme scheme, String gstin, String stateCode,
                                    LocalDate effectiveFrom, RulePack rules, CompositionCategory category) {
        Objects.requireNonNull(scheme, "scheme");
        if ((scheme == GstRegistration.Scheme.COMPOSITION) != (category != null)) {
            throw new RulePackException(scheme == GstRegistration.Scheme.COMPOSITION
                    ? "Choose the composition category - trader, manufacturer, restaurant or services - which "
                    + "decides its rate" : "Only a composition registration has a composition category");
        }
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        if (stateCode == null || rules.state(stateCode).isEmpty()) {
            throw new RulePackException("\"" + stateCode + "\" is not a state code in rule pack " + rules.version());
        }
        String normalised = null;
        if (scheme == GstRegistration.Scheme.UNREGISTERED) {
            if (gstin != null && !gstin.isBlank()) {
                throw new RulePackException("An unregistered business has no GSTIN");
            }
        } else {
            Optional<String> problem = Gstin.problem(gstin, rules);
            if (problem.isPresent()) {
                throw new RulePackException(problem.get());
            }
            normalised = Gstin.normalise(gstin);
            if (!Gstin.stateOf(normalised).equals(stateCode)) {
                throw new RulePackException("GSTIN " + normalised + " is registered in state "
                        + Gstin.stateOf(normalised) + ", not " + stateCode);
            }
        }
        if (scheme == GstRegistration.Scheme.COMPOSITION) {
            requireCompositionEligible(stateCode, effectiveFrom, rules);
        }
        Optional<GstRegistration> latest = history().stream().reduce((a, b) -> b);
        if (latest.isPresent() && !effectiveFrom.isAfter(latest.get().effectiveFrom())) {
            throw new RulePackException("A new registration must take effect after " + latest.get().effectiveFrom()
                    + "; past documents keep the registration they were taxed under");
        }
        if (category != null) {
            rules.compositionRate(category, effectiveFrom);
        }
        GstRegistration registration = new GstRegistration(Ids.newIdString(), effectiveFrom, scheme, normalised,
                stateCode, rules.version(), category);
        String storedGstin = normalised;
        try {
            transactions.inTransaction(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO gst_registration (id, "
                        + "effective_from, scheme, gstin, state_code, rule_pack_version, created_at, "
                        + "composition_category) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, registration.id());
                    ps.setString(2, effectiveFrom.toString());
                    ps.setString(3, scheme.name());
                    ps.setString(4, storedGstin);
                    ps.setString(5, stateCode);
                    ps.setString(6, rules.version());
                    ps.setLong(7, clock.millis());
                    ps.setString(8, category == null ? null : category.name());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not record the GST registration: " + e.getMessage(), e);
        }
        return registration;
    }

    private void requireCompositionEligible(String stateCode, LocalDate effectiveFrom, RulePack rules) {
        // GST's financial year runs April to March, whatever the books' year.
        LocalDate yearStart = LocalDate.of(effectiveFrom.getMonthValue() >= 4
                ? effectiveFrom.getYear() : effectiveFrom.getYear() - 1, 4, 1);
        LocalDate from = yearStart.minusYears(1);
        LocalDate to = yearStart.minusDays(1);
        BigDecimal turnover;
        try {
            turnover = transactions.read(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(CASE kind "
                        + "WHEN 'SALES_INVOICE' THEN taxable_e4 ELSE -taxable_e4 END), 0) FROM sales_document "
                        + "WHERE status = 'POSTED' AND document_date BETWEEN ? AND ?")) {
                    ps.setString(1, from.toString());
                    ps.setString(2, to.toString());
                    try (ResultSet rows = ps.executeQuery()) {
                        rows.next();
                        return MoneyMapper.fromStored(rows.getLong(1), Scale.MONEY);
                    }
                }
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not read last year's turnover: " + e.getMessage(), e);
        }
        BigDecimal limit = rules.compositionLimit(stateCode, effectiveFrom);
        if (turnover.compareTo(limit) > 0) {
            throw new RulePackException("Composition is not available: turnover from " + from + " to " + to + " was "
                    + turnover.stripTrailingZeros().toPlainString() + ", above the limit of "
                    + limit.stripTrailingZeros().toPlainString() + " for state " + stateCode);
        }
    }

    /** The registration in force on {@code date}, if the company had one then. */
    public Optional<GstRegistration> on(LocalDate date) {
        try {
            return transactions.read(connection -> on(connection, date));
        } catch (SQLException e) {
            throw new RulePackException("Could not read GST registrations: " + e.getMessage(), e);
        }
    }

    /** The registration in force on {@code date}, read inside the caller's transaction. */
    public static Optional<GstRegistration> on(Connection connection, LocalDate date) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT id, effective_from, scheme, gstin, "
                + "state_code, rule_pack_version, composition_category FROM gst_registration "
                + "WHERE effective_from <= ? "
                + "ORDER BY effective_from DESC LIMIT 1")) {
            ps.setString(1, date.toString());
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    private static GstRegistration map(ResultSet rows) throws SQLException {
        String category = rows.getString(7);
        return new GstRegistration(rows.getString(1), LocalDate.parse(rows.getString(2)),
                GstRegistration.Scheme.valueOf(rows.getString(3)), rows.getString(4), rows.getString(5),
                rows.getString(6), category == null ? null : CompositionCategory.valueOf(category));
    }

    /** Every registration, oldest first. */
    public List<GstRegistration> history() {
        try {
            return transactions.read(connection -> {
                List<GstRegistration> result = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement("SELECT id, effective_from, scheme, gstin, "
                        + "state_code, rule_pack_version, composition_category FROM gst_registration "
                        + "ORDER BY effective_from");
                     ResultSet rows = ps.executeQuery()) {
                    while (rows.next()) {
                        result.add(map(rows));
                    }
                }
                return result;
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not read GST registrations: " + e.getMessage(), e);
        }
    }
}
