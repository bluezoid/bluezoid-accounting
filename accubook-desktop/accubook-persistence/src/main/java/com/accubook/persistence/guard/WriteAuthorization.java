package com.accubook.persistence.guard;

/**
 * Decides whether a table may be written.
 *
 * <p>An interface rather than a concrete class so the persistence layer keeps no
 * dependency on the module or licensing layers. Persistence asks "may I write
 * this table?" and something above it answers; it never learns what a module,
 * a licence or a subscription is.
 *
 * <p>This is also what lets a test supply a trivial implementation without
 * constructing an entitlement.
 */
@FunctionalInterface
public interface WriteAuthorization {

    /**
     * @param table the table about to be written
     * @return a refusal reason, or empty when the write is permitted
     */
    java.util.Optional<String> refuse(String table);

    /** Permits everything. The default before entitlement is wired in, and for the kernel. */
    static WriteAuthorization permitAll() {
        return table -> java.util.Optional.empty();
    }
}
