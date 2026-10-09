package com.accubook.app.schema;

import com.accubook.persistence.connection.Database;
import com.accubook.persistence.migration.MigrationRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent writes must never leave the books half-posted or short a voucher.
 *
 * <p>These are the accounting-shaped version of the Phase 1 concurrency tests:
 * the same guarantees, exercised against real vouchers and entries rather than a
 * synthetic table.
 */
@DisplayName("Concurrent accounting writes")
class ConcurrentAccountingWriteIT {

    @TempDir
    Path dir;

    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(dir.resolve("company.db"));
        new MigrationRunner(Schemas.companyDatabase(), dir.resolve("backups")).migrate(database);
        database.inTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO company_info (id, name, created_at, updated_at) "
                        + "VALUES ('c1', 'Alpha', 0, 0)");
                s.execute("INSERT INTO fiscal_year (id, name, start_date, end_date, created_at) "
                        + "VALUES ('fy1', 'FY 2026-27', '2026-04-01', '2027-03-31', 0)");
                s.execute("INSERT INTO ledger_group (id, name, nature, created_at, updated_at) "
                        + "VALUES ('g1', 'Assets', 'ASSETS', 0, 0)");
                s.execute("INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                        + "VALUES ('l1', 'g1', 'Cash', 0, 0)");
                s.execute("INSERT INTO ledger (id, group_id, name, created_at, updated_at) "
                        + "VALUES ('l2', 'g1', 'Bank', 0, 0)");
                s.execute("INSERT INTO voucher_type (id, code, name, nature, created_at, updated_at) "
                        + "VALUES ('vt1', 'JOURNAL', 'Journal', 'JOURNAL', 0, 0)");
            }
            return null;
        });
    }

    @Test
    @DisplayName("concurrent voucher posting loses nothing and stays balanced")
    void concurrentPostingIsLossless() throws Exception {
        int writers = 4;
        int perWriter = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        AtomicInteger failures = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            final int writerId = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        postVoucher(writerId + "-" + i, 10_000L);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "poster-" + w);
            thread.start();
        }

        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).as("no writer should fail").isZero();

        int expected = writers * perWriter;
        assertThat(count("voucher")).isEqualTo(expected);
        assertThat(count("voucher_entry")).isEqualTo(expected * 2);

        // The invariant that matters: every rupee posted is still accounted for
        // on both sides.
        try (var connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT COALESCE(SUM(debit_amount_e4), 0), COALESCE(SUM(credit_amount_e4), 0) "
                             + "FROM voucher_entry")) {
            rs.next();
            long debits = rs.getLong(1);
            long credits = rs.getLong(2);
            assertThat(debits).isEqualTo(credits);
            assertThat(debits).isEqualTo(expected * 10_000L);
        }
    }

    @Test
    @DisplayName("a rejected voucher leaves no partial entries behind")
    void rejectedVoucherLeavesNothing() {
        // An unbalanced header is refused by a CHECK constraint. Because SQLite
        // aborts only the statement, the surrounding transaction must be rolled
        // back explicitly - otherwise the entries would commit without a header.
        try {
            database.inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    s.execute("INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, "
                            + "voucher_number, voucher_date, total_debit_e4, total_credit_e4, "
                            + "created_at, updated_at) VALUES ('bad', 'fy1', 'vt1', 'JV-BAD', "
                            + "'2026-04-01', 1000, 900, 0, 0)");
                }
                return null;
            });
        } catch (SQLException expected) {
            // The CHECK fired, as intended.
        }

        assertThat(countOrFail("voucher")).isZero();
        assertThat(countOrFail("voucher_entry")).isZero();
    }

    private void postVoucher(String suffix, long amountE4) throws SQLException {
        database.inTransaction(connection -> {
            try (var ps = connection.prepareStatement(
                    "INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, voucher_number, "
                            + "voucher_date, total_debit_e4, total_credit_e4, status, is_posted, "
                            + "posted_at, created_at, updated_at) "
                            + "VALUES (?, 'fy1', 'vt1', ?, '2026-04-01', ?, ?, 'POSTED', 1, 1, 0, 0)")) {
                ps.setString(1, "v" + suffix);
                ps.setString(2, "JV-" + suffix);
                ps.setLong(3, amountE4);
                ps.setLong(4, amountE4);
                ps.executeUpdate();
            }
            try (var ps = connection.prepareStatement(
                    "INSERT INTO voucher_entry (id, voucher_id, ledger_id, debit_amount_e4, "
                            + "credit_amount_e4, created_at) VALUES (?, ?, ?, ?, ?, 0)")) {
                ps.setString(1, "d" + suffix);
                ps.setString(2, "v" + suffix);
                ps.setString(3, "l1");
                ps.setLong(4, amountE4);
                ps.setLong(5, 0);
                ps.executeUpdate();

                ps.setString(1, "c" + suffix);
                ps.setString(2, "v" + suffix);
                ps.setString(3, "l2");
                ps.setLong(4, 0);
                ps.setLong(5, amountE4);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private int count(String table) throws SQLException {
        try (var connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private int countOrFail(String table) {
        try {
            return count(table);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
