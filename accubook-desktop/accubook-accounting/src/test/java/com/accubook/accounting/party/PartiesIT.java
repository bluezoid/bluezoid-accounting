package com.accubook.accounting.party;

import com.accubook.accounting.chart.ChartException;
import com.accubook.accounting.chart.ChartOfAccounts;
import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.PostingService;
import com.accubook.app.provisioning.CompanyProvisioning;
import com.accubook.app.schema.Schemas;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleState;
import com.accubook.persistence.connection.Database;
import com.accubook.persistence.migration.MigrationRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parties and their ledgers on a genuinely provisioned company. */
@DisplayName("Parties")
class PartiesIT {

    private static final long NOW = 1_000L;

    @TempDir
    Path directory;

    private Database database;
    private Parties parties;
    private ChartOfAccounts chart;

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(directory.resolve("company.db"));
        new MigrationRunner(Schemas.companyDatabase(), directory.resolve("backups")).migrate(database);
        database.inUnguardedTransaction(connection -> {
            CompanyProvisioning.provision(connection, "c1", "Test Co", 4, LocalDate.of(2026, 4, 1));
            return null;
        });
        parties = new Parties(database);
        chart = new ChartOfAccounts(database);
    }

    @AfterEach
    void tearDown() throws SQLException {
        database.close();
    }

    // ------------------------------------------------------------ fixtures

    private String scalar(String sql, String parameter) throws SQLException {
        try (Connection connection = database.openReadConnection();
             var ps = connection.prepareStatement(sql)) {
            if (parameter != null) {
                ps.setString(1, parameter);
            }
            try (ResultSet rows = ps.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private long count(String table) throws SQLException {
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table, null));
    }

    private String groupCodeOf(String ledgerId) throws SQLException {
        return scalar("SELECT g.system_code FROM ledger l JOIN ledger_group g ON g.id = l.group_id "
                + "WHERE l.id = ?", ledgerId);
    }

    /** Post a real voucher touching the party's ledger, so it "has history". */
    private void postAgainst(String ledgerId) throws SQLException {
        EntitlementGuard guard = new EntitlementGuard(id -> ModuleState.ACTIVE);
        String cash = scalar("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'", null);
        new PostingService(database, guard).post(new PostingRequest(
                ModuleId.ACCOUNTING,
                scalar("SELECT id FROM fiscal_year LIMIT 1", null),
                scalar("SELECT id FROM voucher_type WHERE code = 'JOURNAL'", null),
                LocalDate.of(2026, 5, 1), null, "test",
                List.of(new EntryLine(ledgerId, new BigDecimal("100"), BigDecimal.ZERO, null),
                        new EntryLine(cash, BigDecimal.ZERO, new BigDecimal("100"), null)),
                null));
    }

    // --------------------------------------------------------------- tests

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        @DisplayName("a customer gets its own user ledger under Sundry Debtors")
        void customerLedger() throws SQLException {
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, PartyProfile.empty(), NOW);

            assertEquals("SUNDRY_DEBTORS", groupCodeOf(party.ledgerId()));
            assertEquals("Asha Traders", scalar("SELECT name FROM ledger WHERE id = ?", party.ledgerId()));
            assertEquals("0", scalar("SELECT is_system FROM ledger WHERE id = ?", party.ledgerId()));
            assertEquals(party, parties.findByLedgerId(party.ledgerId()).orElseThrow());
        }

        @Test
        @DisplayName("a vendor goes under Sundry Creditors; a party that is both under Sundry Debtors")
        void vendorAndBoth() throws SQLException {
            Party vendor = parties.create("Mehta Supplies", PartyRole.VENDOR, null, NOW);
            Party both = parties.create("Rao & Sons", PartyRole.BOTH, null, NOW);

            assertEquals("SUNDRY_CREDITORS", groupCodeOf(vendor.ledgerId()));
            assertEquals("SUNDRY_DEBTORS", groupCodeOf(both.ledgerId()));
            assertEquals(List.of("Rao & Sons"),
                    parties.customers().stream().map(Party::name).toList());
            assertEquals(List.of("Mehta Supplies", "Rao & Sons"),
                    parties.vendors().stream().map(Party::name).toList());
        }

        @Test
        @DisplayName("R-5: a party may not take the name of an existing account - never merged into it")
        void nameOfExistingLedgerRefused() throws SQLException {
            long ledgersBefore = count("ledger");

            PartyException e = assertThrows(PartyException.class,
                    () -> parties.create("Cash in Hand", PartyRole.CUSTOMER, null, NOW));

            assertTrue(e.getMessage().contains("already used"), e.getMessage());
            assertEquals(ledgersBefore, count("ledger"));
            assertEquals(0, count("party"), "nothing was attached to the cash ledger");
        }

        @Test
        @DisplayName("the clash check ignores case, so two indistinguishable accounts cannot exist")
        void nameClashIgnoresCase() {
            assertThrows(PartyException.class,
                    () -> parties.create("CASH IN HAND", PartyRole.CUSTOMER, null, NOW));
            parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);
            assertThrows(PartyException.class,
                    () -> parties.create("asha traders", PartyRole.VENDOR, null, NOW));
        }

        @Test
        @DisplayName("a party code is unique, ignoring case")
        void codeUnique() {
            parties.create("Asha Traders", PartyRole.CUSTOMER, profileWithCode("C001"), NOW);
            assertThrows(PartyException.class,
                    () -> parties.create("Other", PartyRole.CUSTOMER, profileWithCode("c001"), NOW));
        }

        @Test
        @DisplayName("the profile round-trips, including a fractional credit limit")
        void profileRoundTrips() {
            PartyProfile profile = new PartyProfile("C9", "Asha", "a@x.in", "98", "1 MG Rd", "Pune",
                    "27", "411001", "27abcde1234f1z5", "abcde1234f", new BigDecimal("50000.50"), 30);

            Party created = parties.create("Asha Traders", PartyRole.CUSTOMER, profile, NOW);
            Party read = parties.findById(created.id()).orElseThrow();

            assertEquals("27ABCDE1234F1Z5", read.profile().gstin());
            assertEquals(0, new BigDecimal("50000.50").compareTo(read.profile().creditLimit()));
            assertEquals(30, read.profile().creditDays());
        }

        @Test
        @DisplayName("a blank name is refused")
        void blankName() {
            assertThrows(PartyException.class, () -> parties.create("  ", PartyRole.CUSTOMER, null, NOW));
        }
    }

    @Nested
    @DisplayName("name and activity follow the ledger")
    class FollowsLedger {

        @Test
        @DisplayName("renaming a party renames its ledger")
        void renameParty() throws SQLException {
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);

            parties.rename(party.id(), "Asha Traders Pvt Ltd", NOW);

            assertEquals("Asha Traders Pvt Ltd", scalar("SELECT name FROM ledger WHERE id = ?", party.ledgerId()));
            assertEquals("Asha Traders Pvt Ltd", parties.findById(party.id()).orElseThrow().name());
        }

        @Test
        @DisplayName("renaming the ledger from the chart of accounts renames the party")
        void renameFromChart() throws SQLException {
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);

            chart.rename(party.ledgerId(), "Asha & Co", NOW);

            assertEquals("Asha & Co", scalar("SELECT name FROM party WHERE id = ?", party.id()));
        }

        @Test
        @DisplayName("a rename onto another account's name is refused")
        void renameClash() {
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);
            assertThrows(PartyException.class, () -> parties.rename(party.id(), "Round Off", NOW));
        }

        @Test
        @DisplayName("deactivating a party deactivates its ledger; deactivating from the chart deactivates the party")
        void activeFollowsLedger() throws SQLException {
            Party first = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);
            Party second = parties.create("Mehta Supplies", PartyRole.VENDOR, null, NOW);

            parties.deactivate(first.id(), NOW);
            chart.deactivate(second.ledgerId(), NOW);

            assertEquals("0", scalar("SELECT is_active FROM ledger WHERE id = ?", first.ledgerId()));
            assertFalse(parties.findById(first.id()).orElseThrow().active());
            assertEquals("0", scalar("SELECT is_active FROM party WHERE id = ?", second.id()));

            parties.reactivate(first.id(), NOW);
            assertTrue(parties.findById(first.id()).orElseThrow().active());
        }
    }

    @Nested
    @DisplayName("role changes")
    class RoleChanges {

        @Test
        @DisplayName("an unposted vendor that becomes a customer moves to Sundry Debtors")
        void unpostedMoves() throws SQLException {
            Party party = parties.create("Mehta Supplies", PartyRole.VENDOR, null, NOW);

            parties.changeRole(party.id(), PartyRole.CUSTOMER, NOW);

            assertEquals("SUNDRY_DEBTORS", groupCodeOf(party.ledgerId()));
            assertEquals(PartyRole.CUSTOMER, parties.findById(party.id()).orElseThrow().role());
        }

        @Test
        @DisplayName("R-3: a posted vendor cannot become a plain customer - its balance would flip sign")
        void postedCannotCrossNature() throws SQLException {
            Party party = parties.create("Mehta Supplies", PartyRole.VENDOR, null, NOW);
            postAgainst(party.ledgerId());

            assertThrows(PartyException.class,
                    () -> parties.changeRole(party.id(), PartyRole.CUSTOMER, NOW));

            assertEquals("SUNDRY_CREDITORS", groupCodeOf(party.ledgerId()));
            assertEquals(PartyRole.VENDOR, parties.findById(party.id()).orElseThrow().role());
        }

        @Test
        @DisplayName("a posted vendor may become both, and keeps its ledger where its history is")
        void postedVendorBecomesBoth() throws SQLException {
            Party party = parties.create("Mehta Supplies", PartyRole.VENDOR, null, NOW);
            postAgainst(party.ledgerId());

            parties.changeRole(party.id(), PartyRole.BOTH, NOW);

            assertEquals("SUNDRY_CREDITORS", groupCodeOf(party.ledgerId()));
            assertEquals(PartyRole.BOTH, parties.findById(party.id()).orElseThrow().role());
        }

        @Test
        @DisplayName("the chart of accounts cannot move a party's ledger behind the party's back")
        void chartMoveRefused() throws SQLException {
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);
            String cashGroup = scalar("SELECT id FROM ledger_group WHERE system_code = 'CASH_AND_BANK'", null);

            assertThrows(ChartException.class, () -> chart.moveToGroup(party.ledgerId(), cashGroup, NOW));
            assertEquals("SUNDRY_DEBTORS", groupCodeOf(party.ledgerId()));
        }
    }

    @Nested
    @DisplayName("deletion and integrity")
    class Deletion {

        @Test
        @DisplayName("a party with no history is deleted together with its ledger")
        void deleteUnposted() throws SQLException {
            long ledgersBefore = count("ledger");
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);

            parties.delete(party.id());

            assertEquals(0, count("party"));
            assertEquals(ledgersBefore, count("ledger"));
        }

        @Test
        @DisplayName("a party with history cannot be deleted")
        void deletePostedRefused() throws SQLException {
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);
            postAgainst(party.ledgerId());

            assertThrows(PartyException.class, () -> parties.delete(party.id()));
            assertEquals(1, count("party"));
        }

        @Test
        @DisplayName("deleting a party's ledger from the chart is refused, even with no history")
        void chartDeleteRefused() throws SQLException {
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);

            assertThrows(ChartException.class, () -> chart.delete(party.ledgerId()));
            assertEquals(1, count("party"));
        }

        @Test
        @DisplayName("a party cannot be re-pointed at another ledger, even by direct SQL")
        void ledgerLinkImmutable() throws SQLException {
            Party party = parties.create("Asha Traders", PartyRole.CUSTOMER, null, NOW);
            String cash = scalar("SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'", null);

            assertThrows(SQLException.class, () -> database.inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("UPDATE party SET ledger_id = '" + cash + "'");
                }
            }));
            assertEquals(party.ledgerId(), scalar("SELECT ledger_id FROM party WHERE id = ?", party.id()));
        }
    }

    private static PartyProfile profileWithCode(String code) {
        return new PartyProfile(code, null, null, null, null, null, null, null, null, null, null, null);
    }
}
