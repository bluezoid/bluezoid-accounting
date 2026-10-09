package com.accubook.accounting.posting;

import com.accubook.accounting.posting.internal.PostingInternals;
import com.accubook.module.EntitlementGuard;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Posting inside a transaction the caller already holds.
 *
 * <p>What a feature module gets. It needs no {@code Database} - feature modules
 * are never given one (P4) - only the guarded connection of its own unit of
 * work, so a document's voucher, its stock movements and its own rows commit
 * or roll back together (P7-5).
 *
 * <p>{@link PostingService} delegates here: there is one path to the sealed
 * writer, whichever class a caller holds.
 */
public final class TransactionalPosting {

    private final EntitlementGuard entitlement;
    private final Clock clock;

    public TransactionalPosting(EntitlementGuard entitlement, Clock clock) {
        this.entitlement = Objects.requireNonNull(entitlement, "entitlement");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TransactionalPosting(EntitlementGuard entitlement) {
        this(entitlement, Clock.systemUTC());
    }

    /**
     * Post a voucher in the caller's transaction, which must have been opened
     * with {@code BEGIN IMMEDIATE} - every unit of work is.
     *
     * @throws PostingException if refused; the caller's transaction rolls back
     */
    public PostedVoucher postWithin(Connection connection, PostingRequest request) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(request, "request");
        // Checked on the originating module, never on the ledger: ledger tables
        // are kernel-owned so a downgrade cannot stop the books balancing.
        entitlement.requireWritable(request.originator(), "post a voucher");
        if (request.hasIdempotencyKey()) {
            Optional<PostedVoucher> existing =
                    PostingInternals.findByIdempotencyKey(connection, request.idempotencyKey());
            if (existing.isPresent()) {
                // A retry after a lost response. Creating a second voucher would
                // be the bug, not the retry.
                return existing.get();
            }
        }
        return PostingInternals.post(connection, request, clock.millis());
    }

    /** Reverse a posted voucher in the caller's transaction (P5-2). */
    public PostedVoucher reverseWithin(Connection connection, String originalVoucherId,
                                       ReversalRequest request) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(originalVoucherId, "originalVoucherId");
        Objects.requireNonNull(request, "request");
        entitlement.requireWritable(request.originator(), "reverse a voucher");
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            Optional<PostedVoucher> existing =
                    PostingInternals.findByIdempotencyKey(connection, request.idempotencyKey());
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        return PostingInternals.reverse(connection, originalVoucherId, request, clock.millis());
    }
}
