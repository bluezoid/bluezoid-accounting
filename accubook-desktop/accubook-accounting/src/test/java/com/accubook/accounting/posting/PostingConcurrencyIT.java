package com.accubook.accounting.posting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.accubook.accounting.posting.PostingTestFixture.CASH;
import static com.accubook.accounting.posting.PostingTestFixture.FY;
import static com.accubook.accounting.posting.PostingTestFixture.SALES;
import static com.accubook.accounting.posting.PostingTestFixture.SALES_LEDGER;
import static com.accubook.accounting.posting.PostingTestFixture.VOUCHER_TYPE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q4 and Q8: concurrency and failure recovery.
 *
 * <p>Voucher numbers are what a customer quotes to their accountant and what
 * appears on a statutory return. A duplicate is not a cosmetic defect.
 *
 * <h2>What these tests do and do not prove</h2>
 * They prove the <em>observable</em> contract: many threads posting at once
 * produce unique numbers, balanced books and no lost writes.
 *
 * <p>They do <strong>not</strong> isolate the allocator's own atomicity.
 * Measured during P5: replacing the atomic {@code UPDATE ... last_number + 1}
 * with a racy read-sleep-write still passes every test here, because
 * {@code Database} serialises writers on a lock and {@code BEGIN IMMEDIATE}
 * takes the write lock up front - so two allocations never overlap in the first
 * place.
 *
 * <p>That is worth knowing rather than glossing: the safety comes from the
 * transaction boundary, not from the SQL in the allocator. The atomic form is
 * kept because it is correct on its own terms and does not depend on a caller
 * holding a lock, but these tests are not what would catch its removal. The
 * unique index on {@code idempotency_key} and the {@code UNIQUE (voucher_type_id,
 * fiscal_year_id, voucher_number)} constraint are the real backstops.
 */
class PostingConcurrencyIT {

    @TempDir
    Path directory;

    private PostingTestFixture fixture;

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @BeforeEach
    void setUp() throws SQLException {
        fixture = new PostingTestFixture(directory);
    }

    @AfterEach
    void tearDown() throws SQLException {
        fixture.close();
    }

    private static PostingRequest request(String narration) {
        return new PostingRequest(SALES, FY, VOUCHER_TYPE, LocalDate.of(2026, 4, 1),
                null, narration,
                List.of(EntryLine.debit(CASH, HUNDRED), EntryLine.credit(SALES_LEDGER, HUNDRED)),
                null);
    }

    @Test
    @DisplayName("concurrent posts never allocate the same voucher number")
    void concurrentNumbersAreUnique() throws Exception {
        int threads = 8;
        int perThread = 15;
        PostingService service = fixture.service();
        Set<String> numbers = ConcurrentHashMap.newKeySet();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int id = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        numbers.add(service.post(request("t" + id + "-" + i)).voucherNumber());
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "posting deadlocked");

        int expected = threads * perThread;
        assertAll(
                () -> assertEquals(0, failures.get(), "no posting should fail under contention"),
                () -> assertEquals(expected, numbers.size(), "every voucher number must be unique"),
                () -> assertEquals(expected, fixture.count("voucher")),
                () -> assertEquals(expected * 2L, fixture.count("voucher_entry")));
    }

    @Test
    @DisplayName("the books stay balanced after concurrent posting")
    void concurrentPostingKeepsBooksBalanced() throws Exception {
        PostingService service = fixture.service();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int i = 0; i < 40; i++) {
            final int n = i;
            pool.submit(() -> service.post(request("concurrent-" + n)));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        // The invariant that matters: total debits equal total credits across
        // the whole ledger, however the writes interleaved.
        assertEquals("0", fixture.scalar(
                "SELECT SUM(debit_amount_e4) - SUM(credit_amount_e4) FROM voucher_entry"));
    }

    @Test
    @DisplayName("concurrent replays of one idempotency key create exactly one voucher")
    void concurrentReplayCreatesOne() throws Exception {
        PostingService service = fixture.service();
        String key = "shared-key";
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (int i = 0; i < 6; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.post(new PostingRequest(SALES, FY, VOUCHER_TYPE,
                            LocalDate.of(2026, 4, 1), null, "retry",
                            List.of(EntryLine.debit(CASH, HUNDRED),
                                    EntryLine.credit(SALES_LEDGER, HUNDRED)), key));
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        // Six simultaneous clicks of Save must not become six vouchers. The
        // unique index is the backstop if two reads race the same gap.
        assertEquals(1, fixture.count("voucher"),
                "an idempotency key must yield exactly one voucher, errors=" + errors.get());
    }

    @Test
    @DisplayName("Q8: a failure mid-transaction leaves zero rows")
    void failureLeavesNothing() throws SQLException {
        // Inject a failure after the voucher and entries are written by making
        // the audit insert impossible: a duplicate sequence violates UNIQUE.
        fixture.database().inUnguardedTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO audit_log (id, sequence, occurred_at, action, "
                        + "entity_type, entry_hash) VALUES ('a1', 1, 0, 'SEED', 'test', 'h')");
                // A second row claiming sequence 1 is what the writer will try.
                s.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON audit_log "
                        + "WHEN NEW.action = 'POST' "
                        + "BEGIN SELECT RAISE(ABORT, 'injected failure'); END");
            }
            return null;
        });

        PostingService service = fixture.service();
        assertTrue(assertThrowsPosting(() -> service.post(request("doomed"))));

        assertAll(
                () -> assertEquals(0, fixture.count("voucher"),
                        "the voucher must not survive a failed audit write"),
                () -> assertEquals(0, fixture.count("voucher_entry"),
                        "entries must not survive either"),
                () -> assertEquals(1, fixture.count("audit_log"),
                        "only the seeded row remains"));
    }

    @Test
    @DisplayName("the voucher number counter rolls back with a failed posting")
    void counterRollsBack() throws SQLException {
        PostingService service = fixture.service();
        service.post(request("first"));

        long counterBefore = counter();

        fixture.database().inUnguardedTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON audit_log "
                        + "WHEN NEW.action = 'POST' "
                        + "BEGIN SELECT RAISE(ABORT, 'injected'); END");
            }
            return null;
        });

        assertThrowsPosting(() -> service.post(request("doomed")));

        assertEquals(counterBefore, counter(),
                "a rolled-back posting must not consume a voucher number");
    }

    private long counter() throws SQLException {
        try (Connection connection = fixture.database().openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COALESCE(MAX(last_number), 0) FROM number_counter")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static boolean assertThrowsPosting(Runnable action) {
        try {
            action.run();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }
}
