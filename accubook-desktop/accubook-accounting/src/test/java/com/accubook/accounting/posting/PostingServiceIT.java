package com.accubook.accounting.posting;

import com.accubook.module.ModuleState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static com.accubook.accounting.posting.PostingTestFixture.CASH;
import static com.accubook.accounting.posting.PostingTestFixture.FY;
import static com.accubook.accounting.posting.PostingTestFixture.SALES;
import static com.accubook.accounting.posting.PostingTestFixture.SALES_LEDGER;
import static com.accubook.accounting.posting.PostingTestFixture.VOUCHER_TYPE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The posting core against real SQLite.
 *
 * <p>These answer the twelve questions from the P5 design. Each attacks the core
 * rather than demonstrating it - the question is never "does a correct posting
 * work" but "can an incorrect one get through".
 */
class PostingServiceIT {

    @TempDir
    Path directory;

    private PostingTestFixture fixture;
    private PostingService service;

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @BeforeEach
    void setUp() throws SQLException {
        fixture = new PostingTestFixture(directory);
        service = fixture.service();
    }

    @AfterEach
    void tearDown() throws SQLException {
        fixture.close();
    }

    private PostingRequest balanced(String idempotencyKey) {
        return new PostingRequest(SALES, FY, VOUCHER_TYPE, LocalDate.of(2026, 4, 1),
                null, "Cash sale",
                List.of(EntryLine.debit(CASH, HUNDRED), EntryLine.credit(SALES_LEDGER, HUNDRED)),
                idempotencyKey);
    }

    @Nested
    @DisplayName("normal posting")
    class NormalPosting {

        @Test
        @DisplayName("a balanced voucher posts with its entries")
        void postsSuccessfully() throws SQLException {
            PostedVoucher posted = service.post(balanced(null));

            assertAll(
                    () -> assertEquals(VoucherStatus.POSTED, posted.status()),
                    () -> assertEquals(1, fixture.count("voucher")),
                    () -> assertEquals(2, fixture.count("voucher_entry")),
                    () -> assertEquals(0, posted.total().compareTo(HUNDRED)),
                    () -> assertTrue(!posted.replayed()));
        }

        @Test
        @DisplayName("Q9: the audit entry is written in the same transaction")
        void auditIsAtomicWithPosting() throws SQLException {
            service.post(balanced(null));

            assertAll(
                    () -> assertEquals(1, fixture.count("audit_log")),
                    () -> assertEquals("POST", fixture.scalar("SELECT action FROM audit_log")));
        }

        @Test
        @DisplayName("the header total is derived from the entries, not supplied")
        void headerDerivedFromEntries() throws SQLException {
            // F-2: the reference writes the header separately from the entries,
            // so post-wo-journal.ts:92 sets totals that nothing verifies against
            // the lines. Here the header cannot disagree - it is computed.
            service.post(new PostingRequest(SALES, FY, VOUCHER_TYPE, LocalDate.of(2026, 4, 1),
                    null, "Split",
                    List.of(EntryLine.debit(CASH, new BigDecimal("60.00")),
                            EntryLine.debit(SALES_LEDGER, new BigDecimal("40.00")),
                            EntryLine.credit(SALES_LEDGER, HUNDRED)),
                    null));

            assertEquals("1000000", fixture.scalar("SELECT total_debit_e4 FROM voucher"));
        }
    }

    @Nested
    @DisplayName("Q2 / A-1: what must never reach SQLite")
    class Refusals {

        @Test
        @DisplayName("an unbalanced voucher is refused and writes nothing")
        void refusesUnbalanced() throws SQLException {
            assertThrows(UnbalancedPostingException.class, () ->
                    service.post(new PostingRequest(SALES, FY, VOUCHER_TYPE,
                            LocalDate.of(2026, 4, 1), null, "bad",
                            List.of(EntryLine.debit(CASH, HUNDRED),
                                    EntryLine.credit(SALES_LEDGER, new BigDecimal("90.00"))),
                            null)));

            assertEquals(0, fixture.count("voucher"));
        }

        @Test
        @DisplayName("A-1: an empty voucher is refused - 0 = 0 is not a transaction")
        void refusesEmpty() throws SQLException {
            assertThrows(PostingException.class, () ->
                    service.post(new PostingRequest(SALES, FY, VOUCHER_TYPE,
                            LocalDate.of(2026, 4, 1), null, "empty", List.of(), null)));

            assertEquals(0, fixture.count("voucher"));
        }

        @Test
        @DisplayName("F-3: a sub-paisa imbalance is refused - there is no epsilon")
        void refusesSubPaisa() throws SQLException {
            assertThrows(UnbalancedPostingException.class, () ->
                    service.post(new PostingRequest(SALES, FY, VOUCHER_TYPE,
                            LocalDate.of(2026, 4, 1), null, "off by half a paisa",
                            List.of(EntryLine.debit(CASH, new BigDecimal("100.0050")),
                                    EntryLine.credit(SALES_LEDGER, HUNDRED)),
                            null)));

            assertEquals(0, fixture.count("voucher"));
        }
    }

    @Nested
    @DisplayName("Q6: entitlement")
    class Entitlement {

        @Test
        @DisplayName("a READ_ONLY originator cannot post")
        void readOnlyCannotPost() throws SQLException {
            fixture.setState(SALES, ModuleState.READ_ONLY);

            assertThrows(RuntimeException.class, () -> fixture.service().post(balanced(null)));

            assertEquals(0, fixture.count("voucher"));
        }

        @Test
        @DisplayName("a DORMANT originator cannot post")
        void dormantCannotPost() throws SQLException {
            fixture.setState(SALES, ModuleState.DORMANT);

            assertThrows(RuntimeException.class, () -> fixture.service().post(balanced(null)));

            assertEquals(0, fixture.count("voucher"));
        }
    }

    @Nested
    @DisplayName("Q5: idempotency")
    class Idempotency {

        @Test
        @DisplayName("replaying the same key returns the original and posts nothing new")
        void replayReturnsOriginal() throws SQLException {
            PostedVoucher first = service.post(balanced("key-1"));
            PostedVoucher second = service.post(balanced("key-1"));

            assertAll(
                    () -> assertEquals(first.voucherId(), second.voucherId()),
                    () -> assertTrue(second.replayed(), "the caller should know it was a replay"),
                    () -> assertTrue(!first.replayed()),
                    () -> assertEquals(1, fixture.count("voucher")),
                    () -> assertEquals(2, fixture.count("voucher_entry")));
        }

        @Test
        @DisplayName("the interactive lost-response retry creates exactly one voucher")
        void interactiveRetryIsSafe() throws SQLException {
            // The UI posts, the transaction commits, the response is lost, the
            // user clicks Save again with the same form-scoped key.
            String formKey = "form-" + java.util.UUID.randomUUID();
            service.post(balanced(formKey));
            service.post(balanced(formKey));
            service.post(balanced(formKey));

            assertEquals(1, fixture.count("voucher"));
        }

        @Test
        @DisplayName("different keys create different vouchers")
        void differentKeysDiffer() throws SQLException {
            PostedVoucher first = service.post(balanced("key-a"));
            PostedVoucher second = service.post(balanced("key-b"));

            assertAll(
                    () -> assertNotEquals(first.voucherId(), second.voucherId()),
                    () -> assertEquals(2, fixture.count("voucher")));
        }

        @Test
        @DisplayName("without a key, two identical posts are two vouchers")
        void withoutKeyBothPost() throws SQLException {
            // Correct: two genuine cash sales of the same amount on the same day
            // are two transactions, not a duplicate.
            service.post(balanced(null));
            service.post(balanced(null));

            assertEquals(2, fixture.count("voucher"));
        }
    }

    @Nested
    @DisplayName("Q4: voucher numbering")
    class Numbering {

        @Test
        @DisplayName("numbers are sequential within a scope")
        void numbersAreSequential() {
            PostedVoucher first = service.post(balanced(null));
            PostedVoucher second = service.post(balanced(null));

            assertAll(
                    () -> assertEquals("1", first.voucherNumber()),
                    () -> assertEquals("2", second.voucherNumber()));
        }

        @Test
        @DisplayName("a refused posting does not consume a number permanently")
        void rollbackDoesNotLoseSequence() {
            service.post(balanced(null));
            assertThrows(PostingException.class, () ->
                    service.post(new PostingRequest(SALES, FY, VOUCHER_TYPE,
                            LocalDate.of(2026, 4, 1), null, "bad", List.of(), null)));
            // The failed request never opened a transaction (it fails at
            // construction), so the next number simply follows.
            assertEquals("2", service.post(balanced(null)).voucherNumber());
        }
    }

    @Nested
    @DisplayName("Q11 / Q12: immutability and reversal")
    class Immutability {

        @Test
        @DisplayName("a posted voucher cannot be updated, even by direct SQL")
        void postedCannotBeUpdated() {
            PostedVoucher posted = service.post(balanced(null));

            SQLException e = assertThrows(SQLException.class, () ->
                    fixture.database().inUnguardedTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate(
                                    "UPDATE voucher SET narration = 'tampered' WHERE id = '"
                                            + posted.voucherId() + "'");
                        }
                    }));

            assertTrue(e.getMessage().contains("cannot be modified"), e.getMessage());
        }

        @Test
        @DisplayName("a posted voucher cannot be deleted, even by direct SQL")
        void postedCannotBeDeleted() throws SQLException {
            PostedVoucher posted = service.post(balanced(null));

            assertThrows(SQLException.class, () ->
                    fixture.database().inUnguardedTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate(
                                    "DELETE FROM voucher WHERE id = '" + posted.voucherId() + "'");
                        }
                    }));

            assertEquals(1, fixture.count("voucher"));
        }

        @Test
        @DisplayName("entries of a posted voucher are equally protected")
        void postedEntriesCannotBeModified() {
            service.post(balanced(null));

            assertThrows(SQLException.class, () ->
                    fixture.database().inUnguardedTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate(
                                    "UPDATE voucher_entry SET debit_amount_e4 = 1");
                        }
                    }));
        }

        @Test
        @DisplayName("reversal creates a contra voucher and leaves the original intact")
        void reversalCreatesContra() throws SQLException {
            PostedVoucher original = service.post(balanced(null));

            PostedVoucher reversal = service.reverse(original.voucherId(),
                    new ReversalRequest(SALES, FY, LocalDate.of(2026, 6, 1),
                            "Customer returned goods", "tester", null));

            assertAll(
                    () -> assertEquals(2, fixture.count("voucher")),
                    () -> assertEquals(4, fixture.count("voucher_entry")),
                    () -> assertNotEquals(original.voucherId(), reversal.voucherId()),
                    () -> assertEquals("REVERSED", fixture.scalar(
                            "SELECT status FROM voucher WHERE id = '" + original.voucherId() + "'")),
                    () -> assertEquals(original.voucherId(), fixture.scalar(
                            "SELECT reverses_voucher_id FROM voucher WHERE id = '"
                                    + reversal.voucherId() + "'")));
        }

        @Test
        @DisplayName("the reversal's entries mirror the original's sides")
        void reversalSwapsSides() throws SQLException {
            PostedVoucher original = service.post(balanced(null));
            PostedVoucher reversal = service.reverse(original.voucherId(),
                    new ReversalRequest(SALES, FY, LocalDate.of(2026, 6, 1),
                            "correction", "tester", null));

            // Original debits cash; the reversal must credit it.
            assertEquals("1000000", fixture.scalar(
                    "SELECT credit_amount_e4 FROM voucher_entry WHERE voucher_id = '"
                            + reversal.voucherId() + "' AND ledger_id = '" + CASH + "'"));
        }

        @Test
        @DisplayName("original and reversal sum to zero across the ledger")
        void reversalNetsToZero() throws SQLException {
            PostedVoucher original = service.post(balanced(null));
            service.reverse(original.voucherId(), new ReversalRequest(SALES, FY,
                    LocalDate.of(2026, 6, 1), "correction", "tester", null));

            String net = fixture.scalar(
                    "SELECT SUM(debit_amount_e4) - SUM(credit_amount_e4) FROM voucher_entry");

            assertEquals("0", net, "a reversal must leave the books net-flat");
        }

        @Test
        @DisplayName("A-4: a reversal cannot itself be reversed")
        void cannotReverseAReversal() {
            PostedVoucher original = service.post(balanced(null));
            service.reverse(original.voucherId(), new ReversalRequest(SALES, FY,
                    LocalDate.of(2026, 6, 1), "first", "tester", null));

            PostingException e = assertThrows(PostingException.class, () ->
                    service.reverse(original.voucherId(), new ReversalRequest(SALES, FY,
                            LocalDate.of(2026, 6, 2), "again", "tester", null)));

            assertTrue(e.getMessage().contains("REVERSED"), e.getMessage());
        }

        @Test
        @DisplayName("a reversal must state a reason")
        void reversalNeedsReason() {
            assertThrows(PostingException.class, () ->
                    new ReversalRequest(SALES, FY, LocalDate.of(2026, 6, 1), "  ", "tester", null));
        }

        @Test
        @DisplayName("P5-2: the reversal carries its own date, not the original's")
        void reversalUsesCurrentPeriod() throws SQLException {
            PostedVoucher original = service.post(balanced(null));
            LocalDate currentPeriod = LocalDate.of(2026, 9, 15);

            PostedVoucher reversal = service.reverse(original.voucherId(),
                    new ReversalRequest(SALES, FY, currentPeriod, "prior period", "tester", null));

            assertAll(
                    () -> assertEquals(currentPeriod, reversal.voucherDate()),
                    () -> assertEquals("2026-04-01", fixture.scalar(
                            "SELECT voucher_date FROM voucher WHERE id = '"
                                    + original.voucherId() + "'")));
        }
    }

    @Nested
    @DisplayName("inactive accounts (P6 5.2)")
    class InactiveAccounts {

        private void deactivateSales() throws SQLException {
            fixture.database().inUnguardedTransaction(connection -> {
                try (var st = connection.createStatement()) {
                    st.executeUpdate("UPDATE ledger SET is_active = 0 WHERE id = '" + SALES_LEDGER + "'");
                }
                return null;
            });
        }

        @Test
        @DisplayName("a new posting to an inactive account is refused, leaving nothing behind")
        void postingRefused() throws SQLException {
            deactivateSales();

            PostingException e = assertThrows(PostingException.class, () -> service.post(balanced(null)));

            assertTrue(e.getMessage().contains("inactive"), e.getMessage());
            assertEquals(0, fixture.count("voucher"));
        }

        @Test
        @DisplayName("an unknown account is refused by name, not by a foreign-key error")
        void unknownRefused() {
            PostingException e = assertThrows(PostingException.class, () -> service.post(
                    new PostingRequest(SALES, FY, VOUCHER_TYPE, LocalDate.of(2026, 4, 1), null, "x",
                            List.of(EntryLine.debit(CASH, HUNDRED), EntryLine.credit("nope", HUNDRED)), null)));
            assertTrue(e.getMessage().contains("No such account"), e.getMessage());
        }

        @Test
        @DisplayName("a voucher made before deactivation can still be reversed")
        void reversalStillAllowed() throws SQLException {
            PostedVoucher original = service.post(balanced(null));
            deactivateSales();

            service.reverse(original.voucherId(), new ReversalRequest(SALES, FY,
                    LocalDate.of(2026, 4, 2), "Entered in error", null, null));

            assertEquals(2, fixture.count("voucher"));
        }
    }

    @Nested
    @DisplayName("posting inside the caller's transaction (P7-5)")
    class Within {

        @Test
        @DisplayName("commits with the caller's other work")
        void commitsTogether() throws SQLException {
            fixture.database().inTransaction(connection -> {
                service.postWithin(connection, balanced(null));
                try (var st = connection.createStatement()) {
                    st.executeUpdate("UPDATE company_info SET legal_name = 'Test Co Pvt Ltd'");
                }
                return null;
            });

            assertEquals(1, fixture.count("voucher"));
            assertEquals("Test Co Pvt Ltd", fixture.scalar("SELECT legal_name FROM company_info"));
        }

        @Test
        @DisplayName("is rolled back when the caller's later work fails - no orphan voucher")
        void rollsBackTogether() throws SQLException {
            assertThrows(IllegalStateException.class, () -> fixture.database().inTransaction(connection -> {
                service.postWithin(connection, balanced(null));
                throw new IllegalStateException("stock movement refused");
            }));

            assertEquals(0, fixture.count("voucher"));
            assertEquals(0, fixture.count("voucher_entry"));
            assertEquals(0, fixture.count("audit_log"));
        }

        @Test
        @DisplayName("still checks entitlement")
        void stillChecksEntitlement() {
            fixture.setState(SALES, com.accubook.module.ModuleState.READ_ONLY);
            PostingService readOnly = fixture.service();

            assertThrows(com.accubook.module.ModuleNotWritableException.class, () ->
                    fixture.database().inTransaction(connection -> readOnly.postWithin(connection, balanced(null))));
        }

        @Test
        @DisplayName("reverses inside the caller's transaction too")
        void reverseWithin() throws SQLException {
            PostedVoucher original = service.post(balanced(null));
            fixture.database().inTransaction(connection -> service.reverseWithin(connection, original.voucherId(),
                    new ReversalRequest(SALES, FY, LocalDate.of(2026, 4, 2), "Cancelled", null, null)));

            assertEquals("REVERSED", fixture.scalar("SELECT status FROM voucher WHERE id = '"
                    + original.voucherId() + "'"));
        }
    }
}
