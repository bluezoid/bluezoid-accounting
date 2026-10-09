package com.accubook.accounting.posting;

/**
 * A posting collided with an existing one in a way that could not be resolved.
 *
 * <p>Distinct from an idempotent replay, which is not an error: a replay returns
 * the original voucher. This is raised when the same idempotency key is used for
 * a materially different request, which means the caller has a bug rather than a
 * retry.
 */
public class DuplicatePostingException extends PostingException {
    public DuplicatePostingException(String message) {
        super(message);
    }
}
