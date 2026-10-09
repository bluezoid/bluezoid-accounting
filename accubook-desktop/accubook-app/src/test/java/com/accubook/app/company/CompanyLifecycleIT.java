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
