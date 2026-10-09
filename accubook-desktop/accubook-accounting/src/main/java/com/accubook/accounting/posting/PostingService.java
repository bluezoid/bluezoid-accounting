package com.accubook.accounting.posting;


import com.accubook.module.EntitlementGuard;
import com.accubook.persistence.connection.Database;

import java.sql.SQLException;
import java.util.Objects;


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
    private final TransactionalPosting core;

    public PostingService(Database database, EntitlementGuard entitlement) {
        this(database, entitlement, java.time.Clock.systemUTC());
    }

    /** Clock is injectable so tests need not sleep. */
    public PostingService(Database database, EntitlementGuard entitlement, java.time.Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.core = new TransactionalPosting(entitlement, clock);
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
        try {
            return database.inTransaction(connection -> core.postWithin(connection, request));
        } catch (SQLException e) {
            throw new PostingException("Posting failed and was rolled back: " + e.getMessage(), e);
        }
    }

    /** {@link TransactionalPosting#postWithin}, for a caller that also holds a {@code Database}. */
    public PostedVoucher postWithin(java.sql.Connection connection, PostingRequest request) throws SQLException {
        return core.postWithin(connection, request);
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
        try {
            return database.inTransaction(connection -> core.reverseWithin(connection, originalVoucherId, request));
        } catch (SQLException e) {
            throw new PostingException("Reversal failed and was rolled back: " + e.getMessage(), e);
        }
    }

    /** {@link TransactionalPosting#reverseWithin}. */
    public PostedVoucher reverseWithin(java.sql.Connection connection, String originalVoucherId,
                                       ReversalRequest request) throws SQLException {
        return core.reverseWithin(connection, originalVoucherId, request);
    }
}
