package com.accubook.app.module;

import com.accubook.module.ModuleTransactions;
import com.accubook.persistence.connection.Database;
import com.accubook.persistence.guard.GuardedConnection;
import com.accubook.persistence.guard.WriteAuthorization;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ModuleTransactions} over a company {@link Database}.
 *
 * <p>Writes go through {@link Database#inTransaction}, which wraps the
 * connection in the persistence guard. There is no route from here to the
 * unguarded transaction.
 *
 * <p>Reads use the read pool, which is already {@code query_only}. The read
 * connection is wrapped by the guard as well, with an authorization that
 * refuses every write: {@code query_only} is only a pragma, and a caller holding
 * the raw connection could turn it off. The guard refuses setting pragmas, so
 * the read path stays read-only by two independent mechanisms.
 */
public final class DatabaseModuleTransactions implements ModuleTransactions {

    private static final WriteAuthorization READ_ONLY =
            table -> Optional.of("this connection is for reading only");

    private final Database database;

    public DatabaseModuleTransactions(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public <T> T inTransaction(Work<T> work) throws SQLException {
        Objects.requireNonNull(work, "work");
        return database.inTransaction(work::execute);
    }

    @Override
    public <T> T read(Work<T> work) throws SQLException {
        Objects.requireNonNull(work, "work");
        try (Connection connection = database.openReadConnection()) {
            return work.execute(GuardedConnection.wrap(connection, READ_ONLY));
        }
    }
}
