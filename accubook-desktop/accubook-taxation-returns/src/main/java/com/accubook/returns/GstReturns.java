package com.accubook.returns;

import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.ReversalRequest;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleTransactions;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;
import com.accubook.tax.RulePack;

import java.math.BigDecimal;
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
    private final TransactionalPosting posting;

    public GstReturns(ModuleTransactions transactions, EntitlementGuard entitlement, Supplier<RulePack> rules,
                      Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.posting = new TransactionalPosting(entitlement, clock);
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

    /** GSTR-3B as the books stand, with a suggested set-off. */
    public Gstr3b gstr3b(ReturnPeriod period) {
        requireReadable();
        try {
            return transactions.read(connection -> Gstr3bBuilder.build(connection, period, rules.get()));
        } catch (SQLException e) {
            throw new ReturnException("Could not build GSTR-3B: " + e.getMessage(), e);
        }
    }

    /** GSTR-3B in the portal's JSON field names (P9-15). */
    public String gstr3bJson(ReturnPeriod period) {
        return Gstr3bJson.write(gstr3b(period));
    }

    /**
     * Import a GSTR-2B JSON file downloaded from the portal. It must be the
     * company's GSTIN; an earlier import of the same month is superseded, not
     * deleted. Returns the month and how many documents it holds.
     */
    public Gstr2bImported importGstr2b(String json) {
        entitlement.requireWritable(TaxationReturnsModule.ID, "import GSTR-2B");
        Gstr2bFile file = Gstr2bFile.parse(Objects.requireNonNull(json, "json"));
        String id = Ids.newIdString();
        boolean[] superseded = {false};
        try {
            transactions.inTransaction(connection -> {
                String gstin = Gstr1Builder.requireRegularThroughout(connection, file.period());
                if (!gstin.equals(file.gstin())) {
                    throw new ReturnException("This GSTR-2B is for " + file.gstin() + ", not the company's "
                            + gstin);
                }
                try (PreparedStatement ps = connection.prepareStatement("UPDATE gstr2b_import SET status = "
                        + "'SUPERSEDED' WHERE period_from = ? AND status = 'CURRENT'")) {
                    ps.setString(1, file.period().from().toString());
                    superseded[0] = ps.executeUpdate() > 0;
                }
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO gstr2b_import (id, period_from, "
                        + "gstin, source, source_sha256, imported_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, id);
                    ps.setString(2, file.period().from().toString());
                    ps.setString(3, file.gstin());
                    ps.setString(4, json);
                    ps.setString(5, sha256(json));
                    ps.setLong(6, clock.millis());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO gstr2b_document (id, import_id, "
                        + "doc_type, ctin, trade_name, number, number_key, doc_date, place_of_supply, reverse_charge, "
                        + "itc_available, value_e4, taxable_e4, igst_e4, cgst_e4, sgst_e4, cess_e4) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (Gstr2bFile.Doc d : file.documents()) {
                        int i = 1;
                        ps.setString(i++, Ids.newIdString());
                        ps.setString(i++, id);
                        ps.setString(i++, d.type());
                        ps.setString(i++, d.ctin());
                        ps.setString(i++, d.tradeName());
                        ps.setString(i++, d.number());
                        ps.setString(i++, Gstr2bFile.key(d.number()));
                        ps.setString(i++, d.date().toString());
                        ps.setString(i++, d.placeOfSupply());
                        ps.setInt(i++, d.reverseCharge() ? 1 : 0);
                        ps.setInt(i++, d.itcAvailable() ? 1 : 0);
                        for (BigDecimal amount : List.of(d.value(), d.taxable(), d.igst(), d.cgst(), d.sgst(),
                                d.cess())) {
                            ps.setLong(i++, MoneyMapper.toStored(amount, Scale.MONEY));
                        }
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            throw new ReturnException("Could not import GSTR-2B: " + e.getMessage(), e);
        }
        return new Gstr2bImported(id, file.period(), file.documents().size(), superseded[0]);
    }

    /** The month's purchase documents against its imported GSTR-2B (P9d-3). */
    public Gstr2bReconciliation reconcileGstr2b(ReturnPeriod period) {
        requireReadable();
        try {
            return transactions.read(connection -> Gstr2bReconciler.build(connection, period)).orElseThrow(() ->
                    new ReturnException("No GSTR-2B is imported for " + period.from().getMonth() + " "
                            + period.from().getYear()));
        } catch (SQLException e) {
            throw new ReturnException("Could not reconcile GSTR-2B: " + e.getMessage(), e);
        }
    }

    /**
     * Record that GSTR-1 for {@code period} was filed on the portal, with the
     * JSON as it is now. Refused while a blocking issue remains (P9-16) or the
     * period is already filed. Locks the period's sales (P9-14).
     */
    public FiledReturn markGstr1Filed(ReturnPeriod period, LocalDate filedOn, String acknowledgement) {
        return markFiled("GSTR1", "GSTR-1", period, filedOn, acknowledgement, (connection, pack) -> {
            Gstr1 r = Gstr1Builder.build(connection, period, pack);
            return new Built(r.gstin(), r.issues(), Gstr1Json.write(r), null);
        });
    }

    /** A composition dealer's CMP-08 for a quarter, as the books stand. */
    public Cmp08 cmp08(ReturnPeriod quarter) {
        requireReadable();
        try {
            return transactions.read(connection -> Cmp08Builder.build(connection, quarter, rules.get()));
        } catch (SQLException e) {
            throw new ReturnException("Could not build CMP-08: " + e.getMessage(), e);
        }
    }

    /**
     * Record CMP-08 as filed: snapshot, lock the quarter (P9-14), and book the
     * composition tax - Dr Composition Tax / Cr Composition Tax Payable on the
     * quarter's last day (P9-21). Its payment is an ordinary payment voucher.
     */
    public FiledReturn markCmp08Filed(ReturnPeriod quarter, LocalDate filedOn, String acknowledgement) {
        return markFiled("CMP08", "CMP-08", quarter, filedOn, acknowledgement, (connection, pack) -> {
            Cmp08 r = Cmp08Builder.build(connection, quarter, pack);
            return new Built(r.gstin(), r.issues(), Cmp08Json.write(r), r.compositionTax());
        });
    }

    /** As {@link #markGstr1Filed}, for GSTR-3B. Locks the period's sales and purchases (P9-14). */
    public FiledReturn markGstr3bFiled(ReturnPeriod period, LocalDate filedOn, String acknowledgement) {
        return markFiled("GSTR3B", "GSTR-3B", period, filedOn, acknowledgement, (connection, pack) -> {
            Gstr3b r = Gstr3bBuilder.build(connection, period, pack);
            return new Built(r.gstin(), r.issues(), Gstr3bJson.write(r), null);
        });
    }

    /** @param compositionTax to book on filing (CMP-08), or null */
    private record Built(String gstin, List<Gstr1.Issue> issues, String json, BigDecimal compositionTax) {
    }

    @FunctionalInterface
    private interface Builder {
        Built build(java.sql.Connection connection, RulePack pack) throws SQLException;
    }

    private FiledReturn markFiled(String type, String name, ReturnPeriod period, LocalDate filedOn,
                                  String acknowledgement, Builder builder) {
        entitlement.requireWritable(TaxationReturnsModule.ID, "file a GST return");
        Objects.requireNonNull(filedOn, "filedOn");
        if (filedOn.isBefore(period.to())) {
            throw new ReturnException("A return is filed after its period ends (" + period.to() + ")");
        }
        String id = Ids.newIdString();
        try {
            transactions.inTransaction(connection -> {
                RulePack pack = rules.get();
                Built built = builder.build(connection, pack);
                if (built.issues().stream().anyMatch(Gstr1.Issue::blocking)) {
                    throw new ReturnException(name + " has issues to resolve before it is filed: "
                            + built.issues().stream().filter(Gstr1.Issue::blocking)
                            .map(i -> i.document() + " - " + i.message()).toList());
                }
                String json = built.json();
                String voucherId = built.compositionTax() == null || built.compositionTax().signum() <= 0 ? null
                        : bookCompositionTax(connection, id, period, built.compositionTax());
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO gst_return (id, return_type, "
                        + "period_from, period_to, gstin, filed_on, acknowledgement, snapshot, snapshot_sha256, "
                        + "rule_pack_version, created_at, voucher_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    int i = 1;
                    ps.setString(i++, id);
                    ps.setString(i++, type);
                    ps.setString(i++, period.from().toString());
                    ps.setString(i++, period.to().toString());
                    ps.setString(i++, built.gstin());
                    ps.setString(i++, filedOn.toString());
                    ps.setString(i++, acknowledgement == null || acknowledgement.isBlank() ? null
                            : acknowledgement.strip());
                    ps.setString(i++, json);
                    ps.setString(i++, sha256(json));
                    ps.setString(i++, pack.version());
                    ps.setLong(i++, clock.millis());
                    ps.setString(i, voucherId);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new ReturnException(e.getMessage() != null && e.getMessage().contains("UNIQUE")
                    ? name + " for " + period.from() + " to " + period.to() + " is already filed"
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
                String voucherId;
                String type;
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT voucher_id, return_type FROM gst_return WHERE id = ? AND status = 'FILED'")) {
                    ps.setString(1, returnId);
                    try (ResultSet rows = ps.executeQuery()) {
                        if (!rows.next()) {
                            throw new ReturnException("No filed return " + returnId);
                        }
                        voucherId = rows.getString(1);
                        type = rows.getString(2);
                    }
                }
                // What filing booked is reversed, dated the unlock; filing again books it afresh.
                String reversalId = voucherId == null ? null : posting.reverseWithin(connection, voucherId,
                        new ReversalRequest(TaxationReturnsModule.ID, fiscalYear(connection, Objects.requireNonNull(on,
                                "on")), on, "Unlocked " + type + ": " + reason.strip(), null,
                                "gst-return-unlock:" + returnId)).voucherId();
                try (PreparedStatement ps = connection.prepareStatement("UPDATE gst_return SET status = 'UNLOCKED', "
                        + "unlocked_on = ?, unlock_reason = ?, reversal_voucher_id = ? WHERE id = ?")) {
                    ps.setString(1, on.toString());
                    ps.setString(2, reason.strip());
                    ps.setString(3, reversalId);
                    ps.setString(4, returnId);
                    ps.executeUpdate();
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

    /** P9-21: Dr Composition Tax / Cr Composition Tax Payable, on the quarter's last day. */
    private String bookCompositionTax(java.sql.Connection connection, String returnId, ReturnPeriod quarter,
                                      BigDecimal tax) throws SQLException {
        LocalDate on = quarter.to();
        String narration = "Composition tax, CMP-08 for " + quarter.from() + " to " + quarter.to();
        PostedVoucher voucher = posting.postWithin(connection, new PostingRequest(TaxationReturnsModule.ID,
                fiscalYear(connection, on), single(connection, "SELECT id FROM voucher_type WHERE code = 'JOURNAL'",
                null, "The journal voucher type is missing"), on, null, narration, List.of(
                EntryLine.debit(single(connection, "SELECT id FROM ledger WHERE system_code = 'COMPOSITION_TAX'", null,
                        "The Composition Tax ledger is missing"), tax),
                EntryLine.credit(single(connection, "SELECT id FROM ledger WHERE system_code = "
                        + "'COMPOSITION_TAX_PAYABLE'", null, "The Composition Tax Payable ledger is missing"), tax)),
                "cmp08:" + returnId));
        return voucher.voucherId();
    }

    private static String fiscalYear(java.sql.Connection connection, LocalDate date) throws SQLException {
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

    private static String single(java.sql.Connection connection, String sql, String parameter, String missing)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (parameter != null) {
                ps.setString(1, parameter);
            }
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) {
                    throw new ReturnException(missing);
                }
                return rows.getString(1);
            }
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
