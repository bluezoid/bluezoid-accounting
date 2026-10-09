package com.accubook.module;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The only database access a feature module is given.
 *
 * <p>P4 forbade feature modules from depending on {@code accubook.persistence}
 * but did not say how they store their own tables. Granting that dependency
 * would have handed them {@code Database.inUnguardedTransaction}, which is
 * public - a feature could step around the persistence guard without even
 * needing reflection. This port offers the two things a module legitimately
 * needs and nothing else:
 *
 * <ul>
 *   <li>{@link #inTransaction} - a write transaction whose connection is always
 *       guarded, so a READ_ONLY module's tables refuse writes whatever the
 *       module's own code believes;</li>
 *   <li>{@link #read} - a connection that cannot write at all.</li>
 * </ul>
 *
 * <p>Schema is not the module's business either: its tables are created by the
 * application's migrations (universal schema, ADR-002), and the guard refuses
 * DDL inside a guarded unit of work.
 */
public interface ModuleTransactions {

    /** One unit of work against the open company's database. */
    @FunctionalInterface
    interface Work<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
     * Run {@code work} in a write transaction ({@code BEGIN IMMEDIATE}),
     * committed when it returns and rolled back if it throws.
     */
    <T> T inTransaction(Work<T> work) throws SQLException;

    /** Run {@code work} on a short-lived connection that cannot write. */
    <T> T read(Work<T> work) throws SQLException;
}
