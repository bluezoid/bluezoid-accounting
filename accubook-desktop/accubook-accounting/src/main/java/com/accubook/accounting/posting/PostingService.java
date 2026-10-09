package com.accubook.accounting.posting;

import com.accubook.accounting.posting.internal.PostingInternals;
import com.accubook.module.EntitlementGuard;
import com.accubook.persistence.connection.Database;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * The only way to create accounting data.
 *
 * <p>Every posting - interactive, scheduled, imported, OCR-derived, or from a
 * future payroll or manufacturing module - passes through here. There is no
 * second legitimate API, which is the whole point of the phase: the reference
 * application had a careful posting helper and ten call sites, six of which
 * wrote {@code voucher} themselves, so its "choke point" was a convention.
 *
 * <h2>What one call guarantees</h2>
 * A voucher exists only if <em>all</em> of the following held, atomically:
 *
 * <pre>
 *   entitlement permits WRITE for the originating module
 *   AND the request is structurally valid (>= 2 entries, non-zero, one-sided)
 *   AND total debits == total credits, exactly
 *   AND a voucher number was reserved
 *   AND the audit entry was appended
 *   AND the whole transaction committed
 * </pre>
 *
 * If any part fails, nothing remains - SQLite does not poison a transaction on
 * constraint violation, so the rollback is explicit rather than implied.
 */
public final class PostingService {

    private final Database database;
    private final EntitlementGuard entitlement;
    private final java.time.Clock clock;

    public PostingService(Database database, EntitlementGuard entitlement) {
        this(database, entitlement, java.time.Clock.systemUTC());
    }

    /** Clock is injectable so tests need not sleep. */
    public PostingService(Database database, EntitlementGuard entitlement, java.time.Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Post a voucher.
     *
     * @return the posted voucher; {@link PostedVoucher#replayed()} is true when
     *         an idempotency key matched an earlier commit and nothing new was
     *         written
     * @throws PostingException if the posting was refused; nothing is written
     */
    public PostedVoucher post(PostingRequest request) {
        Objects.requireNonNull(request, "request");
        // Entitlement is checked on the originating module, never on the ledger.
        // Ledger tables are kernel-owned so a downgrade cannot stop the trial
        // balance being maintained - see the P4 test ledgerIsNotOwnedByPayroll.
        entitlement.requireWritable(request.originator(), "post a voucher");

        long now = clock.millis();
        try {
            return database.inTransaction(connection -> postIn(connection, request, now));
        } catch (SQLException e) {
            throw new PostingException("Posting failed and was rolled back: " + e.getMessage(), e);
        }
    }

    /**
     * Post a voucher inside a transaction the caller already holds.
     *
     * <p>For a document whose voucher must commit together with other work -
     * an invoice and its stock movement (P7-5). Nothing differs from
     * {@link #post} except where the transaction boundary lies: the same
     * entitlement check, the same validation, the same sealed writer. The
     * caller's transaction must have been opened with {@code BEGIN IMMEDIATE}
     * (every unit of work is), and its rollback undoes the voucher with
     * everything else.
     *
     * @throws SQLException      on a database failure; the caller rolls back
     * @throws PostingException  if the posting is refused
     */
    public PostedVoucher postWithin(java.sql.Connection connection, PostingRequest request) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(request, "request");
        entitlement.requireWritable(request.originator(), "post a voucher");
        return postIn(connection, request, clock.millis());
    }

    private static PostedVoucher postIn(java.sql.Connection connection, PostingRequest request, long now)
            throws SQLException {
        if (request.hasIdempotencyKey()) {
            Optional<PostedVoucher> existing =
                    PostingInternals.findByIdempotencyKey(connection, request.idempotencyKey());
            if (existing.isPresent()) {
                // The caller retried after a lost response. The voucher
                // is already in the books; creating a second would be
                // the bug, not the retry.
                return existing.get();
            }
        }
        return PostingInternals.post(connection, request, now);
    }

    /**
     * Reverse a posted voucher with a contra voucher.
     *
     * <p>Per P5-2 the reversal is dated in the fiscal period the caller supplies
     * - the current open one - and the original is never edited. Both records
     * remain in the trial balance and sum to zero.
     */
    public PostedVoucher reverse(String originalVoucherId, ReversalRequest request) {
        Objects.requireNonNull(originalVoucherId, "originalVoucherId");
        Objects.requireNonNull(request, "request");
        entitlement.requireWritable(request.originator(), "reverse a voucher");

        long now = clock.millis();
        try {
            return database.inTransaction(connection -> reverseIn(connection, originalVoucherId, request, now));
        } catch (SQLException e) {
            throw new PostingException("Reversal failed and was rolled back: " + e.getMessage(), e);
        }
    }

    /** {@link #reverse} inside a transaction the caller holds; see {@link #postWithin}. */
    public PostedVoucher reverseWithin(java.sql.Connection connection, String originalVoucherId,
                                       ReversalRequest request) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(originalVoucherId, "originalVoucherId");
        Objects.requireNonNull(request, "request");
        entitlement.requireWritable(request.originator(), "reverse a voucher");
        return reverseIn(connection, originalVoucherId, request, clock.millis());
    }

    private static PostedVoucher reverseIn(java.sql.Connection connection, String originalVoucherId,
                                           ReversalRequest request, long now) throws SQLException {
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<PostedVoucher> existing =
                    PostingInternals.findByIdempotencyKey(connection, request.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return PostingInternals.reverse(connection, originalVoucherId, request, now);
    }
}
