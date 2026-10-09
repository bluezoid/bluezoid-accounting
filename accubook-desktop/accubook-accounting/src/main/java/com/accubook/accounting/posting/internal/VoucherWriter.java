package com.accubook.accounting.posting.internal;

import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingException;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.ReversalRequest;
import com.accubook.accounting.posting.VoucherStatus;
import com.accubook.platform.id.Ids;
import com.accubook.platform.money.MoneyMapper;
import com.accubook.platform.money.Scale;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Writes vouchers and their entries. The only code in the application that does.
 *
 * <p>Package-private in an unexported package, so nothing outside this module can
 * reference it - not a repository, not a use case, not a feature module. That is
 * the structural difference from the reference application, whose posting helper
 * was public and therefore optional: ten call sites, six of which wrote
 * {@code voucher} themselves.
 *
 * <p>Every method here assumes it is already inside a transaction opened with
 * {@code BEGIN IMMEDIATE}. It never opens or commits one; that belongs to the
 * caller, so a voucher and its entries cannot land in separate transactions.
 */
public final class VoucherWriter {

    private VoucherWriter() {
    }

    /** Look for a previously committed voucher with this idempotency key. */
    static Optional<PostedVoucher> findByIdempotencyKey(Connection connection, String key)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, voucher_number, voucher_date, total_debit_e4, status "
                        + "FROM voucher WHERE idempotency_key = ?")) {
            select.setString(1, key);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PostedVoucher(
                        rows.getString(1),
                        rows.getString(2),
                        LocalDate.parse(rows.getString(3)),
                        MoneyMapper.fromStored(rows.getLong(4), Scale.MONEY),
                        VoucherStatus.valueOf(rows.getString(5)),
                        true));
            }
        }
    }

    /**
     * Write a voucher and its entries, posted.
     *
     * <p>The header totals are computed <em>from the entries</em>, never accepted
     * from the caller. The reference application writes them separately -
     * {@code post-wo-journal.ts:92} sets {@code totalDebit: amount,
     * totalCredit: amount} - so its header is balanced by construction while
     * nothing checks that the entries agree with it. Deriving the header here
     * makes that class of disagreement unrepresentable.
     */
    static PostedVoucher post(Connection connection, PostingRequest request, long now)
            throws SQLException {
        long totalDebitE4 = 0;
        long totalCreditE4 = 0;
        for (EntryLine entry : request.entries()) {
            totalDebitE4 += MoneyMapper.toStored(entry.debit(), Scale.MONEY);
            totalCreditE4 += MoneyMapper.toStored(entry.credit(), Scale.MONEY);
        }
        // Re-checked at scaled-integer precision. PostingRequest validated in
        // BigDecimal; this catches anything the conversion could disturb, and
        // costs one comparison.
        if (totalDebitE4 != totalCreditE4) {
            throw new PostingException(
                    "Voucher does not balance at storage precision: "
                            + totalDebitE4 + " != " + totalCreditE4);
        }
        if (totalDebitE4 <= 0) {
            throw new PostingException("A voucher must move a non-zero amount");
        }

        requireActiveLedgers(connection, request.entries());

        String scope = request.voucherTypeId() + ":" + request.fiscalYearId();
        long number = NumberAllocator.next(connection, scope, now);
        String voucherNumber = String.valueOf(number);
        String voucherId = Ids.newIdString();

        insertVoucher(connection, voucherId, request.fiscalYearId(), request.voucherTypeId(),
                voucherNumber, request.voucherDate(), request.referenceNo(), request.narration(),
                totalDebitE4, VoucherStatus.POSTED, null, null, null,
                request.idempotencyKey(), request.originator().value(), now);

        insertEntries(connection, voucherId, request.entries(), now);

        AuditWriter.record(connection, "POST", "voucher", voucherId,
                "number=" + voucherNumber + " total=" + totalDebitE4
                        + " originator=" + request.originator(), now);

        return new PostedVoucher(voucherId, voucherNumber, request.voucherDate(),
                MoneyMapper.fromStored(totalDebitE4, Scale.MONEY), VoucherStatus.POSTED, false);
    }

    /**
     * Write the contra voucher that reverses {@code originalId}, and mark the
     * original REVERSED.
     *
     * <p>The original's amounts, date and number are untouched; only its status
     * changes, which is the single transition the immutability trigger permits.
     */
    static PostedVoucher reverse(Connection connection, String originalId,
                                 ReversalRequest request, long now) throws SQLException {
        OriginalVoucher original = loadForReversal(connection, originalId);

        List<EntryLine> reversedEntries = loadEntries(connection, originalId).stream()
                .map(EntryLine::reversed)
                .toList();

        String scope = original.voucherTypeId + ":" + request.fiscalYearId();
        long number = NumberAllocator.next(connection, scope, now);
        String voucherNumber = String.valueOf(number);
        String reversalId = Ids.newIdString();

        insertVoucher(connection, reversalId, request.fiscalYearId(), original.voucherTypeId,
                voucherNumber, request.reversalDate(), original.voucherNumber,
                "Reversal of " + original.voucherNumber + ": " + request.reason(),
                original.totalE4, VoucherStatus.POSTED,
                originalId, request.reason(), request.postedBy(),
                request.idempotencyKey(), request.originator().value(), now);

        insertEntries(connection, reversalId, reversedEntries, now);

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE voucher SET status = 'REVERSED', updated_at = ? WHERE id = ?")) {
            update.setLong(1, now);
            update.setString(2, originalId);
            update.executeUpdate();
        }

        AuditWriter.record(connection, "REVERSE", "voucher", originalId,
                "reversedBy=" + reversalId + " reason=" + request.reason(), now);

        return new PostedVoucher(reversalId, voucherNumber, request.reversalDate(),
                MoneyMapper.fromStored(original.totalE4, Scale.MONEY),
                VoucherStatus.POSTED, false);
    }

    /**
     * Every ledger a new posting names must exist and be active (P6 §5.2).
     *
     * <p>Deactivating an account hides it from new business; its history and
     * balance stay in the books. Only {@link #post} checks this - a reversal
     * may still touch a deactivated account, or a wrong entry made before the
     * account was closed could never be corrected.
     */
    private static void requireActiveLedgers(Connection connection, List<EntryLine> entries)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT name, is_active FROM ledger WHERE id = ?")) {
            for (String ledgerId : entries.stream().map(EntryLine::ledgerId).distinct().toList()) {
                select.setString(1, ledgerId);
                try (ResultSet rows = select.executeQuery()) {
                    if (!rows.next()) {
                        throw new PostingException("No such account: " + ledgerId);
                    }
                    if (rows.getInt(2) == 0) {
                        throw new PostingException("The account \"" + rows.getString(1)
                                + "\" is inactive and cannot receive new postings. Reactivate it first.");
                    }
                }
            }
        }
    }

    private static void insertVoucher(Connection connection, String id, String fiscalYearId,
                                      String voucherTypeId, String voucherNumber, LocalDate date,
                                      String referenceNo, String narration, long totalE4,
                                      VoucherStatus status, String reversesId, String reason,
                                      String postedBy, String idempotencyKey, String originator,
                                      long now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, "
                        + "voucher_date, reference_no, narration, total_debit_e4, total_credit_e4, "
                        + "status, is_posted, posted_at, reverses_voucher_id, reversal_reason, "
                        + "posted_by, idempotency_key, originator_module, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, id);
            insert.setString(2, fiscalYearId);
            insert.setString(3, voucherTypeId);
            insert.setString(4, voucherNumber);
            insert.setString(5, date.toString());
            insert.setString(6, referenceNo);
            insert.setString(7, narration);
            insert.setLong(8, totalE4);
            insert.setLong(9, totalE4);
            insert.setString(10, status.name());
            insert.setInt(11, status.isPosted() ? 1 : 0);
            insert.setLong(12, now);
            insert.setString(13, reversesId);
            insert.setString(14, reason);
            insert.setString(15, postedBy);
            insert.setString(16, idempotencyKey);
            insert.setString(17, originator);
            insert.setLong(18, now);
            insert.setLong(19, now);
            insert.executeUpdate();
        }
    }

    private static void insertEntries(Connection connection, String voucherId,
                                      List<EntryLine> entries, long now) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                        + "credit_amount_e4, narration, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (EntryLine entry : entries) {
                insert.setString(1, Ids.newIdString());
                insert.setString(2, voucherId);
                insert.setString(3, entry.ledgerId());
                insert.setLong(4, MoneyMapper.toStored(entry.debit(), Scale.MONEY));
                insert.setLong(5, MoneyMapper.toStored(entry.credit(), Scale.MONEY));
                insert.setString(6, entry.narration());
                insert.setLong(7, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static List<EntryLine> loadEntries(Connection connection, String voucherId)
            throws SQLException {
        List<EntryLine> entries = new java.util.ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT ledger_id, debit_amount_e4, credit_amount_e4, narration "
                        + "FROM voucher_entry WHERE voucher_id = ? ORDER BY id")) {
            select.setString(1, voucherId);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    entries.add(new EntryLine(
                            rows.getString(1),
                            MoneyMapper.fromStored(rows.getLong(2), Scale.MONEY),
                            MoneyMapper.fromStored(rows.getLong(3), Scale.MONEY),
                            rows.getString(4)));
                }
            }
        }
        return entries;
    }

    private record OriginalVoucher(String voucherTypeId, String voucherNumber, long totalE4) {
    }

    private static OriginalVoucher loadForReversal(Connection connection, String id)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT voucher_type_id, voucher_number, total_debit_e4, status "
                        + "FROM voucher WHERE id = ?")) {
            select.setString(1, id);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    throw new PostingException("No such voucher to reverse: " + id);
                }
                VoucherStatus status = VoucherStatus.valueOf(rows.getString(4));
                if (!status.canTransitionTo(VoucherStatus.REVERSED)) {
                    // A-4: refuses reversing a reversal, and refuses reversing a
                    // draft, which never reached the ledger.
                    throw new PostingException("Cannot reverse a voucher that is " + status
                            + "; only a POSTED voucher can be reversed");
                }
                return new OriginalVoucher(rows.getString(1), rows.getString(2), rows.getLong(3));
            }
        }
    }
}
