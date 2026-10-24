package com.accubook.app.company;

import com.accubook.persistence.connection.Database;
import com.accubook.persistence.location.UnsupportedStorageLocationException;
import com.accubook.persistence.lock.ExclusiveLock;
import com.accubook.persistence.lock.LockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Company lifecycle, locking, identity and data-safety guarantees.
 *
 * <p>Each test here corresponds to a way a customer could lose their books, and
 * asserts that it does not happen.
 */
@DisplayName("Company lifecycle")
class CompanyLifecycleIT {

    @Test
    @DisplayName("a new company is fully provisioned")
    void newCompanyIsProvisioned(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            service.openCompany(company.id());

            // Chart of accounts, voucher types and an opening fiscal year, so
            // the company is usable immediately rather than after manual setup.
            assertThat(service.chartOfAccounts()).isNotEmpty();
            assertThat(service.chartOfAccounts())
                    .extracting(CompanyService.LedgerSummary::systemCode)
                    .contains("CASH_IN_HAND", "OPENING_BALANCE_EQUITY", "RETAINED_EARNINGS");
        }
    }

    @Test
    @DisplayName("D-32: a company chooses its fiscal start month and the year its books begin")
    void fiscalYearChosen(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("  Calendar Traders ", 1, java.time.LocalDate.of(2025, 6, 15));
            service.openCompany(company.id());

            assertThat(company.name()).isEqualTo("Calendar Traders");
            assertThat(service.readIdentityFromFile(company.id()).orElseThrow().fiscalYearStartMonth()).isEqualTo(1);
            String firstYear = service.readOpenCompany(c -> {
                try (Statement s = c.createStatement();
                     java.sql.ResultSet r = s.executeQuery("SELECT start_date, end_date FROM fiscal_year")) {
                    r.next();
                    return r.getString(1) + " " + r.getString(2);
                }
            });
            assertThat(firstYear).isEqualTo("2025-01-01 2025-12-31");
            assertThatThrownBy(() -> service.createCompany(" ", 4, java.time.LocalDate.now()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.createCompany("X", 13, java.time.LocalDate.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the company file carries its own identity (P2-4)")
    void companyFileIsSelfIdentifying(@TempDir Path dir) throws Exception {
        String id;
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            id = company.id();

            CompanyService.CompanyIdentity identity =
                    service.readIdentityFromFile(id).orElseThrow();
            assertThat(identity.id()).isEqualTo(id);
            assertThat(identity.name()).isEqualTo("Alpha Traders");
            assertThat(identity.fiscalYearStartMonth()).isEqualTo(4);
        }

        // The decisive property: identity is readable from the file alone,
        // without app.db. This is what makes "hand it to your CA" and "restore
        // onto a new machine" work.
        try (Database standalone = Database.open(dir.resolve("companies").resolve(id).resolve("company.db"));
             var connection = standalone.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT id, name FROM company_info")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("id")).isEqualTo(id);
            assertThat(rs.getString("name")).isEqualTo("Alpha Traders");
        }
    }

    @Test
    @DisplayName("identity survives a copy of the file to another location")
    void identitySurvivesFileCopy(@TempDir Path dir, @TempDir Path elsewhere) throws Exception {
        String id;
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            id = company.id();
            service.openCompany(id);
        }

        Path source = dir.resolve("companies").resolve(id).resolve("company.db");
        Path copy = elsewhere.resolve("handed-to-accountant.db");
        Files.copy(source, copy);

        try (Database standalone = Database.open(copy);
             var connection = standalone.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT name FROM company_info")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("name")).isEqualTo("Alpha Traders");
        }
    }

    @Test
    @DisplayName("a second installation instance is refused")
    void secondInstanceIsRefused(@TempDir Path dir) throws Exception {
        try (CompanyService first = CompanyService.start(dir)) {
            assertThat(first).isNotNull();
            // Two instances would each hold their own view of the open company.
            assertThatThrownBy(() -> CompanyService.start(dir))
                    .isInstanceOf(LockException.class);
        }
        // Released on close, so a restart works.
        try (CompanyService again = CompanyService.start(dir)) {
            assertThat(again.companies()).isEmpty();
        }
    }

    @Test
    @DisplayName("an open company holds a per-file lock")
    void openCompanyHoldsFileLock(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            service.openCompany(company.id());

            Path companyLock = dir.resolve("companies").resolve(company.id()).resolve("company.lock");
            assertThat(ExclusiveLock.readHolder(companyLock)).isPresent();

            assertThatThrownBy(() -> ExclusiveLock.acquire(companyLock))
                    .isInstanceOf(LockException.class);
        }
    }

    @Test
    @DisplayName("switching companies releases the previous lock")
    void switchingReleasesPreviousLock(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company alpha = service.createCompany("Alpha Traders");
            Company beta = service.createCompany("Beta Industries");

            service.openCompany(alpha.id());
            service.openCompany(beta.id());

            // Alpha's lock must be free again, or a company could never be
            // reopened after a switch.
            Path alphaLock = dir.resolve("companies").resolve(alpha.id()).resolve("company.lock");
            try (ExclusiveLock reacquired = ExclusiveLock.acquire(alphaLock)) {
                assertThat(reacquired).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("refuses to start in a sync folder (P2-3)")
    void refusesSyncFolder(@TempDir Path dir) throws Exception {
        Path syncFolder = dir.resolve("Dropbox").resolve("AccuBook");
        Files.createDirectories(syncFolder);

        assertThatThrownBy(() -> CompanyService.start(syncFolder))
                .isInstanceOf(UnsupportedStorageLocationException.class)
                .hasMessageContaining("file-sync folder");

        // Nothing was created, so a refusal leaves no partial installation.
        assertThat(Files.exists(syncFolder.resolve("app.db"))).isFalse();
    }

    @Test
    @DisplayName("balances are computed from the journal (P2-2)")
    void balancesAreComputed(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            service.openCompany(company.id());

            // No postings yet, so every balance is zero and the trial balance
            // balances trivially. There is no cached column to disagree with it.
            CompanyService.TrialBalanceTotals totals = service.trialBalanceTotals();
            assertThat(totals.balanced()).isTrue();
            assertThat(totals.totalDebits()).isEqualByComparingTo("0");

            assertThat(service.ledgerBalances()).isNotEmpty();
            assertThat(service.ledgerBalances())
                    .allSatisfy(b -> assertThat(b.balance()).isEqualByComparingTo("0"));
        }
    }

    @Test
    @DisplayName("an unposted voucher's entries never reach a ledger balance")
    void draftEntriesAreNotBalances(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            service.openCompany(company.id());

            // A DRAFT voucher with entries. Same defect class as P6 3.1: a
            // posted-only filter placed on a LEFT JOIN of voucher, while SUM
            // reads voucher_entry, filters nothing.
            service.companyDatabase().inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    s.executeUpdate("INSERT INTO voucher (id, fiscal_year_id, voucher_type_id, "
                            + "voucher_number, voucher_date, total_debit_e4, total_credit_e4, status, "
                            + "is_posted, created_at, updated_at) SELECT 'd1', "
                            + "(SELECT id FROM fiscal_year LIMIT 1), "
                            + "(SELECT id FROM voucher_type WHERE code = 'JOURNAL'), "
                            + "'D1', '2026-05-01', 1000000, 1000000, 'DRAFT', 0, 0, 0");
                    s.executeUpdate("INSERT INTO voucher_entry (id, voucher_id, ledger_id, "
                            + "debit_amount_e4, credit_amount_e4, created_at) SELECT 'e1', 'd1', id, "
                            + "1000000, 0, 0 FROM ledger WHERE system_code = 'CASH_IN_HAND'");
                    s.executeUpdate("INSERT INTO voucher_entry (id, voucher_id, ledger_id, "
                            + "debit_amount_e4, credit_amount_e4, created_at) SELECT 'e2', 'd1', id, "
                            + "0, 1000000, 0 FROM ledger WHERE system_code = 'OPENING_BALANCE_EQUITY'");
                }
                return null;
            });

            assertThat(service.ledgerBalances())
                    .allSatisfy(b -> assertThat(b.balance()).isEqualByComparingTo("0"));
            assertThat(service.trialBalanceTotals().totalDebits()).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("the open company can be read from outside the service, but never written")
    void readOpenCompanyCannotWrite(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            service.openCompany(company.id());

            String name = service.readOpenCompany(connection -> {
                try (Statement s = connection.createStatement();
                     var rows = s.executeQuery("SELECT name FROM company_info")) {
                    rows.next();
                    return rows.getString(1);
                }
            });
            assertThat(name).isEqualTo("Alpha Traders");

            assertThatThrownBy(() -> service.readOpenCompany(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("UPDATE company_info SET name = 'Changed'");
                }
            })).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> service.readOpenCompany(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.execute("PRAGMA query_only = 0");
                }
            })).isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("no cached balance column exists to drift")
    void noCachedBalanceColumn(@TempDir Path dir) throws Exception {
        String id;
        try (CompanyService service = CompanyService.start(dir)) {
            id = service.createCompany("Alpha Traders").id();
        }

        // Decision P2-2 asserted structurally. The reference keeps two
        // independent running balances with nothing reconciling them.
        try (Database db = Database.open(dir.resolve("companies").resolve(id).resolve("company.db"));
             var connection = db.openReadConnection();
             Statement statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT sql FROM sqlite_master WHERE name = 'ledger'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).doesNotContain("current_balance");
        }
    }

    @Test
    @DisplayName("creating a company twice with the same name is refused")
    void duplicateCompanyNameRefused(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            service.createCompany("Alpha Traders");
            assertThatThrownBy(() -> service.createCompany("Alpha Traders"))
                    .isInstanceOf(Exception.class);
        }
    }
}
