package com.accubook.persistence.connection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transaction semantics.
 *
 * <p>The rollback test here is the most important one in Phase 1. SQLite differs
 * from PostgreSQL in a way that is easy to miss and expensive to get wrong: a
 * constraint violation aborts only the offending <em>statement</em>, leaving the
 * transaction open and usable. A catch-and-continue would therefore commit half
 * a voucher, which is precisely the class of corruption the reference
 * application fought.
 */
@DisplayName("UnitOfWork")
class UnitOfWorkIT {

    @TempDir
    Path dir;

    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(dir.resolve("uow.db"));
        database.inUnguardedTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE entry ("
                        + "id TEXT PRIMARY KEY, "
                        + "amount_e4 INTEGER NOT NULL CHECK (amount_e4 >= 0)) STRICT");
            }
            return null;
        });
    }

    @Test
    @DisplayName("commits work that succeeds")
    void commitsOnSuccess() throws SQLException {
        database.inTransaction(connection -> {
            insert(connection, "a", 100);
            return null;
        });
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("rolls the WHOLE transaction back when one statement violates a constraint")
    void rollsBackEntireTransactionOnConstraintViolation() {
        // In PostgreSQL the failed statement poisons the transaction, so this
        // behaviour comes for free. In SQLite it does not: without the explicit
        // rollback in UnitOfWork, rows 'a' and 'c' would commit and 'b' would
        // simply be missing - a silently partial journal entry.
        assertThatThrownBy(() -> database.inTransaction(connection -> {
            insert(connection, "a", 100);
            insert(connection, "b", -1);   // violates CHECK
            insert(connection, "c", 300);
            return null;
        })).isInstanceOf(SQLException.class);

        assertThat(countOrFail()).as("nothing may survive a failed transaction").isZero();
    }

    @Test
    @DisplayName("rolls back on an unchecked exception too")
    void rollsBackOnRuntimeException() {
        assertThatThrownBy(() -> database.inTransaction(connection -> {
            insert(connection, "a", 100);
            throw new IllegalStateException("domain rule violated");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countOrFail()).isZero();
    }

    @Test
    @DisplayName("uses BEGIN IMMEDIATE so busy_timeout applies to writers")
    void usesBeginImmediate() throws Exception {
        // A deferred transaction that upgrades read->write fails instantly with
        // SQLITE_BUSY regardless of busy_timeout. With BEGIN IMMEDIATE the
        // second writer waits and then succeeds, so no write is lost.
        int writers = 4;
        int perWriter = 25;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicInteger failures = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            final int writerId = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        final String id = writerId + "-" + i;
                        database.inTransaction(connection -> {
                            insert(connection, id, 10);
                            return null;
                        });
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            thread.start();
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).as("no writer should fail").isZero();
        assertThat(count()).as("no write may be lost").isEqualTo(writers * perWriter);
    }

    @Test
    @DisplayName("a failed transaction leaves the connection usable")
    void connectionSurvivesFailure() throws SQLException {
        assertThatThrownBy(() -> database.inTransaction(connection -> {
            insert(connection, "bad", -5);
            return null;
        })).isInstanceOf(SQLException.class);

        database.inTransaction(connection -> {
            insert(connection, "good", 42);
            return null;
        });
        assertThat(count()).isEqualTo(1);
    }

    private void insert(Connection connection, String id, long amount) throws SQLException {
        try (var ps = connection.prepareStatement("INSERT INTO entry VALUES (?, ?)")) {
            ps.setString(1, id);
            ps.setLong(2, amount);
            ps.executeUpdate();
        }
    }

    /** Unchecked wrapper, so assertions can be written inline after a lambda. */
    private int countOrFail() {
        try {
            return count();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int count() throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM entry")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
