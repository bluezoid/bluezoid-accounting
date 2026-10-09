package com.accubook.accounting.posting.internal;

import com.accubook.accounting.posting.PostedVoucher;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.ReversalRequest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * The seam between the public service and the sealed writer.
 *
 * <p>Public only because {@code PostingService} lives in a sibling package;
 * the package itself is not exported, so this type is unreachable from outside
 * the module. Being able to name a class is not the same as being able to load
 * it: JPMS refuses at compile time, and the P4 persistence guard refuses the
 * write at runtime even if reflection gets this far.
 */
public final class PostingInternals {

    private PostingInternals() {
    }

    public static Optional<PostedVoucher> findByIdempotencyKey(Connection connection, String key)
            throws SQLException {
        return VoucherWriter.findByIdempotencyKey(connection, key);
    }

    public static PostedVoucher post(Connection connection, PostingRequest request, long now)
            throws SQLException {
        return VoucherWriter.post(connection, request, now);
    }

    public static PostedVoucher reverse(Connection connection, String originalId,
                                        ReversalRequest request, long now) throws SQLException {
        return VoucherWriter.reverse(connection, originalId, request, now);
    }
}
