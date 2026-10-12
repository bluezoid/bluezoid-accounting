package com.accubook.returns;

import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleTransactions;
import com.accubook.platform.id.Ids;
import com.accubook.tax.RulePack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * GST returns: preview, portal JSON, mark filed, unlock (P9d).
 *
 * <p>Marking a return filed stores the JSON exactly as generated with its
 * SHA-256, and locks the period: documents dated in it can no longer be posted
 * or cancelled (P9-14, enforced in the schema). Unlocking is recorded with a
 * reason, never a delete.
 */
public final class GstReturns {

    private final ModuleTransactions transactions;
    private final EntitlementGuard entitlement;
    private final Supplier<RulePack> rules;
    private final Clock clock;

    public GstReturns(ModuleTransactions transactions, EntitlementGuard entitlement, Supplier<RulePack> rules,
                      Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** GSTR-1 as the books stand, with the issues that block or qualify filing it. */
    public Gstr1 gstr1(ReturnPeriod period) {
        requireReadable();
        try {
            return transactions.read(connection -> Gstr1Builder.build(connection, period, rules.get()));
        } catch (SQLException e) {
            throw new ReturnException("Could not build GSTR-1: " + e.getMessage(), e);
        }
    }

    /** The portal offline-upload JSON for a GSTR-1 (P9-15). */
    public String gstr1Json(ReturnPeriod period) {
        return Gstr1Json.write(gstr1(period));
    }

    /**
     * Record that GSTR-1 for {@code period} was filed on the portal, with the
     * JSON as it is now. Refused while a blocking issue remains (P9-16) or the
     * period is already filed.
     */
    public FiledReturn markGstr1Filed(ReturnPeriod period, LocalDate filedOn, String acknowledgement) {
        entitlement.requireWritable(TaxationReturnsModule.ID, "file a GST return");
        Objects.requireNonNull(filedOn, "filedOn");
        if (filedOn.isBefore(period.to())) {
            throw new ReturnException("A return is filed after its period ends (" + period.to() + ")");
        }
        String id = Ids.newIdString();
        try {
            transactions.inTransaction(connection -> {
                RulePack pack = rules.get();
                Gstr1 gstr1 = Gstr1Builder.build(connection, period, pack);
                if (!gstr1.fileable()) {
                    throw new ReturnException("GSTR-1 has issues to resolve before it is filed: "
                            + gstr1.issues().stream().filter(Gstr1.Issue::blocking)
                            .map(i -> i.document() + " - " + i.message()).toList());
                }
                String json = Gstr1Json.write(gstr1);
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO gst_return (id, return_type, "
                        + "period_from, period_to, gstin, filed_on, acknowledgement, snapshot, snapshot_sha256, "
                        + "rule_pack_version, created_at) VALUES (?, 'GSTR1', ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, period.from().toString());
                    ps.setString(3, period.to().toString());
                    ps.setString(4, gstr1.gstin());
                    ps.setString(5, filedOn.toString());
                    ps.setString(6, acknowledgement == null || acknowledgement.isBlank() ? null
                            : acknowledgement.strip());
                    ps.setString(7, json);
                    ps.setString(8, sha256(json));
                    ps.setString(9, pack.version());
                    ps.setLong(10, clock.millis());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new ReturnException(e.getMessage() != null && e.getMessage().contains("UNIQUE")
                    ? "GSTR-1 for " + period.from() + " to " + period.to() + " is already filed"
                    : "Could not record the return: " + e.getMessage(), e);
        }
        return filed().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Unlock a filed return, so documents dated in its period can change again.
     * An audited action: the reason and date stay on the record with the
     * original snapshot (P9-14).
     */
    public void unlock(String returnId, String reason, LocalDate on) {
        entitlement.requireWritable(TaxationReturnsModule.ID, "unlock a filed GST return");
        if (reason == null || reason.isBlank()) {
            throw new ReturnException("Unlocking a filed return needs a reason; it stays on the record");
        }
        try {
            transactions.inTransaction(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("UPDATE gst_return SET status = 'UNLOCKED', "
                        + "unlocked_on = ?, unlock_reason = ? WHERE id = ? AND status = 'FILED'")) {
                    ps.setString(1, Objects.requireNonNull(on, "on").toString());
                    ps.setString(2, reason.strip());
                    ps.setString(3, returnId);
                    if (ps.executeUpdate() == 0) {
                        throw new ReturnException("No filed return " + returnId);
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw new ReturnException("Could not unlock the return: " + e.getMessage(), e);
        }
    }

    /** Every filed return, latest period first. */
    public List<FiledReturn> filed() {
        requireReadable();
        try {
            return transactions.read(connection -> {
                List<FiledReturn> result = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement("SELECT id, return_type, period_from, "
                        + "period_to, gstin, filed_on, acknowledgement, snapshot_sha256, status, unlock_reason "
                        + "FROM gst_return ORDER BY period_from DESC, created_at DESC");
                     ResultSet rows = ps.executeQuery()) {
                    while (rows.next()) {
                        result.add(new FiledReturn(rows.getString(1), rows.getString(2),
                                new ReturnPeriod(LocalDate.parse(rows.getString(3)), LocalDate.parse(rows.getString(4))),
                                rows.getString(5), LocalDate.parse(rows.getString(6)), rows.getString(7),
                                rows.getString(8), "UNLOCKED".equals(rows.getString(9)), rows.getString(10)));
                    }
                }
                return result;
            });
        } catch (SQLException e) {
            throw new ReturnException("Could not read filed returns: " + e.getMessage(), e);
        }
    }

    /** The JSON a return was filed with, exactly as stored. */
    public String snapshot(String returnId) {
        requireReadable();
        try {
            return transactions.read(connection -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT snapshot FROM gst_return WHERE id = ?")) {
                    ps.setString(1, returnId);
                    try (ResultSet rows = ps.executeQuery()) {
                        if (!rows.next()) {
                            throw new ReturnException("No such return: " + returnId);
                        }
                        return rows.getString(1);
                    }
                }
            });
        } catch (SQLException e) {
            throw new ReturnException("Could not read the return: " + e.getMessage(), e);
        }
    }

    private void requireReadable() {
        if (!entitlement.isReadable(TaxationReturnsModule.ID)) {
            throw new ReturnException("Tax returns are not part of this licence");
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
