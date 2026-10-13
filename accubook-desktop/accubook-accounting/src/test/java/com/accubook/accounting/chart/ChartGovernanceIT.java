package com.accubook.accounting.chart;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Account governance against a genuinely provisioned company.
 *
 * <p>Each test corresponds to a defect measured in the reference application, so
 * the suite doubles as a record of what was wrong there and why the rule exists
 * here.
 */
class ChartGovernanceIT {

    /** 4 kernel, Sales and Purchases (P7c), 15 GST (P9-7), 3 TDS/TCS (P9-13), 2 composition (P9-21). */
    private static final long SEEDED_LEDGERS = 26;


    @TempDir
    Path directory;

    private ChartFixture fixture;
    private ChartOfAccounts chart;

    @BeforeEach
    void setUp() throws SQLException {
        fixture = new ChartFixture(directory);
        chart = fixture.chart();
    }

    @AfterEach
    void tearDown() throws SQLException {
        fixture.close();
    }

    @Nested
    @DisplayName("provisioning")
    class Provisioning {

        @Test
        @DisplayName("seeds 18 groups, 6 ledgers and 8 voucher types")
        void seedsExpectedCounts() throws SQLException {
            // 4 kernel ledgers (P2-1), Sales and Purchases (P7c), whose modules
            // are in every plan, and the GST ledgers (P9-7; GST is kernel, B-7).
            assertAll(
                    () -> assertEquals(18, fixture.count("ledger_group")),
                    () -> assertEquals(SEEDED_LEDGERS, fixture.count("ledger")),
                    () -> assertEquals(8, fixture.count("voucher_type")));
        }

        @Test
        @DisplayName("P2-1 holds: no ledger is seeded for a module that is not in every plan")
        void doesNotSeedModuleLedgers() throws SQLException {
            // Seeding an account for a module a build lacks would put a line on
            // the trial balance nothing can ever post to. COGS would contradict
            // B-1 (periodic 5a: no cost of goods sold at issue). Sales,
            // Purchases and the per-component GST ledgers ARE seeded: their
            // modules are in every plan. The reference's single GST Input and
            // GST Output ledgers are not (P9-7).
            assertTrue(chart.findBySystemCode("SALES").isPresent());
            assertTrue(chart.findBySystemCode("PURCHASES").isPresent());
            assertTrue(chart.findBySystemCode("GST_OUTPUT_CGST").isPresent());
            assertTrue(chart.findBySystemCode("GST_RCM_CESS").isPresent());
            for (String code : new String[]{"GST_INPUT", "GST_OUTPUT", "COGS",
                    "INVENTORY_ADJUSTMENT"}) {
                assertTrue(chart.findBySystemCode(code).isEmpty(),
                        code + " must not be seeded by the kernel");
            }
        }

        @Test
        @DisplayName("every seeded ledger is a system account")
        void seededLedgersAreSystem() {
            assertTrue(chart.allAccounts().stream().allMatch(LedgerAccount::system));
        }

        @Test
        @DisplayName("accounts resolve by stable code, and carry the right nature")
        void resolvesBySystemCode() {
            assertAll(
                    () -> assertEquals(AccountNature.ASSETS,
                            chart.findBySystemCode("CASH_IN_HAND").orElseThrow().nature()),
                    () -> assertEquals(AccountNature.EQUITY,
                            chart.findBySystemCode("RETAINED_EARNINGS").orElseThrow().nature()),
                    () -> assertEquals(AccountNature.EXPENSES,
                            chart.findBySystemCode("ROUND_OFF").orElseThrow().nature()));
        }
    }

    @Nested
    @DisplayName("R-1 / R-2: system account protection")
    class SystemProtection {

        @Test
        @DisplayName("a system ledger cannot be deleted even when never posted to")
        void systemLedgerCannotBeDeleted() throws SQLException {
            // The reference hard-deletes any ledger with no postings. On a fresh
            // company that is every system ledger it just created.
            String roundOff = fixture.ledgerIdBySystemCode("ROUND_OFF");

            ChartException e = assertThrows(ChartException.class, () -> chart.delete(roundOff));

            assertAll(
                    () -> assertTrue(e.getMessage().contains("cannot be deleted"), e.getMessage()),
                    () -> assertEquals(SEEDED_LEDGERS, fixture.count("ledger")));
        }

        @Test
        @DisplayName("a trigger refuses the deletion even by direct SQL")
        void triggerRefusesDirectDelete() throws SQLException {
            String roundOff = fixture.ledgerIdBySystemCode("ROUND_OFF");

            assertThrows(SQLException.class, () ->
                    fixture.database().inUnguardedTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate("DELETE FROM ledger WHERE id = '" + roundOff + "'");
                        }
                    }));

            assertEquals(SEEDED_LEDGERS, fixture.count("ledger"));
        }

        @Test
        @DisplayName("a system ledger cannot be deactivated")
        void systemLedgerCannotBeDeactivated() throws SQLException {
            String cash = fixture.ledgerIdBySystemCode("CASH_IN_HAND");

            assertThrows(ChartException.class, () -> chart.deactivate(cash, 0L));

            assertTrue(chart.findBySystemCode("CASH_IN_HAND").orElseThrow().active());
        }

        @Test
        @DisplayName("a system ledger cannot lose its system_code")
        void systemCodeIsImmutable() throws SQLException {
            String roundOff = fixture.ledgerIdBySystemCode("ROUND_OFF");

            assertThrows(SQLException.class, () ->
                    fixture.database().inUnguardedTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate(
                                    "UPDATE ledger SET system_code = NULL WHERE id = '"
                                            + roundOff + "'");
                        }
                    }));

            assertTrue(chart.findBySystemCode("ROUND_OFF").isPresent());
        }
    }

    @Nested
    @DisplayName("P6-2 / R-4: renaming")
    class Renaming {

        @Test
        @DisplayName("a system ledger may be renamed and still resolves by code")
        void renameDoesNotBreakResolution() throws SQLException {
            String roundOff = fixture.ledgerIdBySystemCode("ROUND_OFF");
            long ledgersBefore = fixture.count("ledger");

            chart.rename(roundOff, "Rounding A/c", 1L);

            // The reference resolves this account by the string "Round Off" via
            // getOrCreateNamedLedger, so this rename would make the next invoice
            // create a second one and split the balance across both.
            LedgerAccount resolved = chart.findBySystemCode("ROUND_OFF").orElseThrow();
            assertAll(
                    () -> assertEquals("Rounding A/c", resolved.name()),
                    () -> assertEquals(roundOff, resolved.id()),
                    () -> assertEquals(ledgersBefore, fixture.count("ledger"), "no duplicate was created"));
        }

        @Test
        @DisplayName("renaming back also works - identity never depended on the label")
        void renameIsReversible() throws SQLException {
            String roundOff = fixture.ledgerIdBySystemCode("ROUND_OFF");
            long ledgersBefore = fixture.count("ledger");

            chart.rename(roundOff, "Rounding A/c", 1L);
            chart.rename(roundOff, "Round Off", 2L);

            assertEquals("Round Off", chart.findBySystemCode("ROUND_OFF").orElseThrow().name());
        }

        @Test
        @DisplayName("a blank name is refused")
        void blankNameRefused() throws SQLException {
            String roundOff = fixture.ledgerIdBySystemCode("ROUND_OFF");

            assertThrows(ChartException.class, () -> chart.rename(roundOff, "   ", 1L));
        }
    }

    @Nested
    @DisplayName("R-3: moving between groups")
    class MovingGroups {

        @Test
        @DisplayName("a user account with no postings may move across natures")
        void unpostedAccountMayMove() throws SQLException {
            String ledger = fixture.createUserLedger("Misc", "SUNDRY_DEBTORS");
            String incomeGroup = fixture.groupIdBySystemCode("SALES_ACCOUNTS");

            chart.moveToGroup(ledger, incomeGroup, 1L);

            assertEquals(AccountNature.INCOME, chart.findById(ledger).orElseThrow().nature());
        }

        @Test
        @DisplayName("moving within the same nature is always allowed")
        void sameNatureMoveAllowed() throws SQLException {
            String ledger = fixture.createUserLedger("Petty Cash", "CASH_AND_BANK");
            String otherAssets = fixture.groupIdBySystemCode("SUNDRY_DEBTORS");

            chart.moveToGroup(ledger, otherAssets, 1L);

            assertEquals(AccountNature.ASSETS, chart.findById(ledger).orElseThrow().nature());
        }
    }

    @Nested
    @DisplayName("A-9: group lifecycle")
    class GroupLifecycle {

        @Test
        @DisplayName("a system group cannot be deleted")
        void systemGroupCannotBeDeleted() throws SQLException {
            String group = fixture.groupIdBySystemCode("SUNDRY_DEBTORS");

            assertThrows(ChartException.class, () -> chart.deleteGroup(group));

            assertEquals(18, fixture.count("ledger_group"));
        }

        @Test
        @DisplayName("a group containing ledgers cannot be deleted")
        void groupWithLedgersCannotBeDeleted() throws SQLException {
            String group = fixture.groupIdBySystemCode("CASH_AND_BANK");

            ChartException e = assertThrows(ChartException.class, () -> chart.deleteGroup(group));

            // Foreign keys default OFF per connection (measured in P2), so this
            // must not rely on the FK to refuse.
            assertTrue(e.getMessage().contains("system") || e.getMessage().contains("accounts"),
                    e.getMessage());
        }

        @Test
        @DisplayName("a group with sub-groups cannot be deleted, even by direct SQL")
        void groupWithChildrenRefusedByTrigger() throws SQLException {
            String assetsRoot = fixture.groupIdBySystemCode("ASSETS_ROOT");

            assertThrows(SQLException.class, () ->
                    fixture.database().inUnguardedTransaction(connection -> {
                        try (Statement s = connection.createStatement()) {
                            return s.executeUpdate(
                                    "DELETE FROM ledger_group WHERE id = '" + assetsRoot + "'");
                        }
                    }));

            assertEquals(18, fixture.count("ledger_group"));
        }
    }

    @Nested
    @DisplayName("user account lifecycle")
    class UserAccounts {

        @Test
        @DisplayName("an unused user account can be deleted")
        void unusedUserAccountDeletable() throws SQLException {
            long seeded = fixture.count("ledger");
            String ledger = fixture.createUserLedger("Stationery", "INDIRECT_EXPENSES");
            assertEquals(seeded + 1, fixture.count("ledger"));

            chart.delete(ledger);

            assertEquals(seeded, fixture.count("ledger"));
        }

        @Test
        @DisplayName("a user account can be deactivated and reactivated")
        void deactivateAndReactivate() throws SQLException {
            String ledger = fixture.createUserLedger("Old Expense", "INDIRECT_EXPENSES");

            chart.deactivate(ledger, 1L);
            assertFalse(chart.findById(ledger).orElseThrow().active());

            chart.reactivate(ledger, 2L);
            assertTrue(chart.findById(ledger).orElseThrow().active());
        }
    }
}
