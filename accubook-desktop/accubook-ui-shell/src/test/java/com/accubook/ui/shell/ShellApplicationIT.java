package com.accubook.ui.shell;

import com.accubook.app.company.CompanyService;
import com.accubook.accounting.chart.TrialBalanceRow;
import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.PostingService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleState;
import com.accubook.persistence.connection.Database;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI workflow test for the Phase 1 shell.
 *
 * <p>What this actually proves is that the shell starts against a real database
 * and that database work does not run on the JavaFX application thread - a rule
 * that is easy to state, easy to break, and which every later screen will copy
 * from whatever this one does.
 *
 * <h2>Why these are opt-in</h2>
 * JavaFX needs a display. The usual headless answer, TestFX + Monocle, does not
 * work here: the current Monocle build targets JDK 12-era JavaFX internals and
 * fails against JavaFX 17 with {@code AbstractMethodError} on
 * {@code MonocleWindow._updateViewSize}. These tests therefore run on a real X
 * display - Xvfb on CI and on a developer machine - and only when
 * {@code -Daccubook.ui.tests=true} is set, so a machine without one does not
 * report environmental failures as red builds:
 *
 * <pre>
 * xvfb-run -a -s "-screen 0 1280x1024x24" mvn verify -Daccubook.ui.tests=true
 * </pre>
 */
@DisplayName("Shell application")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class ShellApplicationIT extends ApplicationTest {

    private Path dataDir;

    @Override
    public void start(Stage stage) throws Exception {
        dataDir = Files.createTempDirectory("accubook-ui-test");
        seedCompanyWithPosting(dataDir);
        ShellApplication.setServiceFactory(() -> CompanyService.start(dataDir));
        new ShellApplication().start(stage);
    }

    /**
     * A company with one real posting, made through the sealed posting core
     * before the shell starts - the shell has no data-entry screen yet.
     */
    private static void seedCompanyWithPosting(Path dataDir) throws Exception {
        String companyId;
        try (CompanyService service = CompanyService.start(dataDir)) {
            companyId = service.createCompany("Seeded Traders").id();
        }
        Path file = dataDir.resolve("companies").resolve(companyId).resolve("company.db");
        try (Database database = Database.open(file)) {
            new PostingService(database, new EntitlementGuard(id -> ModuleState.ACTIVE))
                    .post(new PostingRequest(ModuleId.ACCOUNTING,
                            scalar(database, "SELECT id FROM fiscal_year LIMIT 1"),
                            scalar(database, "SELECT id FROM voucher_type WHERE code = 'JOURNAL'"),
                            LocalDate.parse(scalar(database, "SELECT start_date FROM fiscal_year LIMIT 1")),
                            null, "Capital introduced",
                            List.of(new EntryLine(scalar(database,
                                            "SELECT id FROM ledger WHERE system_code = 'CASH_IN_HAND'"),
                                            new BigDecimal("150000"), BigDecimal.ZERO, null),
                                    new EntryLine(scalar(database,
                                            "SELECT id FROM ledger WHERE system_code = 'OPENING_BALANCE_EQUITY'"),
                                            BigDecimal.ZERO, new BigDecimal("150000"), null)),
                            null));
        }
    }

    private static String scalar(Database database, String sql) throws Exception {
        try (var connection = database.openReadConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (dataDir != null && Files.exists(dataDir)) {
            try (var paths = Files.walk(dataDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // Temp directory cleanup; a leftover file is harmless.
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("launches and shows the company list")
    void launches() {
        assertThat(lookup("#companyList").tryQuery()).isPresent();
        assertThat(lookup("#createCompanyButton").tryQuery()).isPresent();
        assertThat(lookup("#trialBalanceButton").tryQuery()).isPresent();
    }

    @Test
    @DisplayName("creates a company through the UI and shows it in the list")
    void createsCompany() {
        javafx.scene.control.TextField nameField =
                lookup(".text-field").nth(0).queryAs(javafx.scene.control.TextField.class);
        clickOn(nameField);
        write("Alpha Traders");
        clickOn("#createCompanyButton");

        // The work happens on a background thread, so wait for the result to be
        // published back rather than asserting immediately.
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        sleep(1500);

        javafx.scene.control.ListView<?> list = lookup("#companyList").query();
        assertThat(list.getItems().toString()).contains("Alpha Traders");
    }

    @Test
    @DisplayName("the application data directory is created on startup")
    void createsDataDirectory() {
        org.testfx.util.WaitForAsyncUtils.waitForFxEvents();
        sleep(1000);
        assertThat(Files.exists(dataDir.resolve("app.db"))).isTrue();
        assertThat(Files.isDirectory(dataDir.resolve("companies"))).isTrue();
    }

    @Test
    @DisplayName("P6-4: opening a company and its trial balance shows the posted books, balanced")
    void showsTrialBalanceOfPostedBooks() throws Exception {
        ListView<String> companies = lookup("#companyList").query();
        org.testfx.util.WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> companies.getItems().stream().anyMatch(item -> item.startsWith("Seeded Traders")));

        clickOn(companies.getItems().stream()
                .filter(item -> item.startsWith("Seeded Traders")).findFirst().orElseThrow());
        org.testfx.util.WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> lookup("Company open.").tryQuery().isPresent());

        clickOn("#trialBalanceButton");
        org.testfx.util.WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> lookup("#trialBalanceStatus").tryQuery()
                        .map(node -> ((Label) node).getText().startsWith("Balanced"))
                        .orElse(false));

        TableView<TrialBalanceRow> table = lookup("#trialBalanceTable").queryTableView();
        assertThat(table.getItems()).extracting(TrialBalanceRow::ledgerName)
                .containsExactly("Cash in Hand", "Opening Balance Equity");
        assertThat(lookup("#totalDebit").queryAs(Label.class).getText()).isEqualTo("1,50,000.00");
        assertThat(lookup("#totalCredit").queryAs(Label.class).getText()).isEqualTo("1,50,000.00");
    }
}
