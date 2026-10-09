package com.accubook.app.company;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies decision B-6: each company's data is physically isolated in its own
 * SQLite file.
 *
 * <p>Isolation is asserted structurally rather than by querying with a filter.
 * The reference application relies on every one of 110 route files remembering
 * an {@code organizationId} predicate, with no database-level enforcement; these
 * tests confirm the desktop product does not need that discipline, because one
 * company's rows live in a file the other company's connection never opens.
 */
@DisplayName("Company isolation")
class CompanyIsolationIT {

    @Test
    @DisplayName("each company gets its own database file")
    void eachCompanyHasItsOwnFile(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company alpha = service.createCompany("Alpha Traders");
            Company beta = service.createCompany("Beta Industries");

            Path alphaFile = dir.resolve("companies").resolve(alpha.id()).resolve("company.db");
            Path betaFile = dir.resolve("companies").resolve(beta.id()).resolve("company.db");

            assertThat(alphaFile).exists();
            assertThat(betaFile).exists();
            assertThat(alphaFile).isNotEqualTo(betaFile);
        }
    }

    @Test
    @DisplayName("each company has an independent chart of accounts")
    void chartsOfAccountsAreIndependent(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company alpha = service.createCompany("Alpha Traders");
            Company beta = service.createCompany("Beta Industries");

            service.openCompany(alpha.id());
            List<CompanyService.LedgerSummary> alphaLedgers = service.chartOfAccounts();

            service.openCompany(beta.id());
            List<CompanyService.LedgerSummary> betaLedgers = service.chartOfAccounts();

            assertThat(alphaLedgers).isNotEmpty();
            assertThat(betaLedgers).hasSameSizeAs(alphaLedgers);

            // Same seed content, but distinct rows in distinct files.
            assertThat(alphaLedgers).extracting(CompanyService.LedgerSummary::id)
                    .doesNotContainAnyElementsOf(
                            betaLedgers.stream().map(CompanyService.LedgerSummary::id).toList());
        }
    }

    @Test
    @DisplayName("app.db holds no accounting data")
    void appDatabaseHoldsNoAccountingData(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            service.createCompany("Alpha Traders");
        }

        // The registry knows a company exists; it does not know what is in its
        // books. That separation is what lets a subscription survive restoring
        // an old company file, and vice versa.
        try (var appDb = com.accubook.persistence.connection.Database.open(dir.resolve("app.db"));
             var connection = appDb.openReadConnection();
             var statement = connection.createStatement();
             var rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            StringBuilder tables = new StringBuilder();
            while (rs.next()) {
                tables.append(rs.getString(1)).append(' ');
            }
            assertThat(tables.toString())
                    .doesNotContain("voucher")
                    .doesNotContain("ledger")
                    .doesNotContain("audit_log");
        }
    }

    @Test
    @DisplayName("companies are listed in the registry after creation")
    void companiesAreRegistered(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            service.createCompany("Alpha Traders");
            service.createCompany("Beta Industries");

            List<Company> companies = service.companies();
            assertThat(companies).hasSize(2);
            assertThat(companies).extracting(Company::name)
                    .containsExactlyInAnyOrder("Alpha Traders", "Beta Industries");
        }
    }

    @Test
    @DisplayName("data survives closing and reopening the installation")
    void dataSurvivesRestart(@TempDir Path dir) throws Exception {
        String companyId;
        int ledgerCount;
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            companyId = company.id();
            service.openCompany(companyId);
            ledgerCount = service.chartOfAccounts().size();
        }

        try (CompanyService service = CompanyService.start(dir)) {
            service.openCompany(companyId);
            assertThat(service.chartOfAccounts()).hasSize(ledgerCount);
        }
    }

    @Test
    @DisplayName("the data directory is created outside any install directory")
    void dataDirectoryLayout(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");

            assertThat(Files.exists(dir.resolve("app.db"))).isTrue();
            assertThat(Files.isDirectory(dir.resolve("companies"))).isTrue();
            assertThat(Files.isDirectory(dir.resolve("backups"))).isTrue();
            assertThat(Files.isDirectory(dir.resolve("companies").resolve(company.id()))).isTrue();
            assertThat(Files.isDirectory(
                    dir.resolve("companies").resolve(company.id()).resolve("attachments"))).isTrue();
        }
    }
}
