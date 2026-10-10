package com.accubook.tax;

import com.accubook.module.ModuleTransactions;
import com.accubook.platform.id.Ids;

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
     */
    public GstRegistration register(GstRegistration.Scheme scheme, String gstin, String stateCode,
                                    LocalDate effectiveFrom, RulePack rules) {
        Objects.requireNonNull(scheme, "scheme");
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
        Optional<GstRegistration> latest = history().stream().reduce((a, b) -> b);
        if (latest.isPresent() && !effectiveFrom.isAfter(latest.get().effectiveFrom())) {
            throw new RulePackException("A new registration must take effect after " + latest.get().effectiveFrom()
                    + "; past documents keep the registration they were taxed under");
        }
        GstRegistration registration = new GstRegistration(Ids.newIdString(), effectiveFrom, scheme, normalised,
                stateCode, rules.version());
        String storedGstin = normalised;
        try {
            transactions.inTransaction(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO gst_registration (id, "
                        + "effective_from, scheme, gstin, state_code, rule_pack_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, registration.id());
                    ps.setString(2, effectiveFrom.toString());
                    ps.setString(3, scheme.name());
                    ps.setString(4, storedGstin);
                    ps.setString(5, stateCode);
                    ps.setString(6, rules.version());
                    ps.setLong(7, clock.millis());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not record the GST registration: " + e.getMessage(), e);
        }
        return registration;
    }

    /** The registration in force on {@code date}, if the company had one then. */
    public Optional<GstRegistration> on(LocalDate date) {
        return history().stream().filter(r -> !r.effectiveFrom().isAfter(date)).reduce((a, b) -> b);
    }

    /** Every registration, oldest first. */
    public List<GstRegistration> history() {
        try {
            return transactions.read(connection -> {
                List<GstRegistration> result = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement("SELECT id, effective_from, scheme, gstin, "
                        + "state_code, rule_pack_version FROM gst_registration ORDER BY effective_from");
                     ResultSet rows = ps.executeQuery()) {
                    while (rows.next()) {
                        result.add(new GstRegistration(rows.getString(1), LocalDate.parse(rows.getString(2)),
                                GstRegistration.Scheme.valueOf(rows.getString(3)), rows.getString(4),
                                rows.getString(5), rows.getString(6)));
                    }
                }
                return result;
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not read GST registrations: " + e.getMessage(), e);
        }
    }
}
