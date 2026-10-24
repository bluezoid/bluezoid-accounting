package com.accubook.ui.shell;

import com.accubook.accounting.chart.TrialBalanceRow;
import com.accubook.app.company.Company;
import com.accubook.app.company.CompanyService;
import com.accubook.accounting.posting.EntryLine;
import com.accubook.accounting.posting.PostingRequest;
import com.accubook.accounting.posting.PostingService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleState;
import com.accubook.persistence.connection.Database;
import com.accubook.ui.api.DateField;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Month;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UI workflow test for the shell: the company picker and the main window
 * (Phase 11S), against a real database. Database work never runs on the JavaFX
 * thread - the screens' transactions refuse it.
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

    private void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, condition);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void openCompany(String name) throws Exception {
        ListView<Company> companies = lookup("#companyList").query();
        waitFor(() -> companies.getItems().stream().anyMatch(c -> c.name().equals(name)));
        interact(() -> companies.getSelectionModel().select(companies.getItems().stream()
                .filter(c -> c.name().equals(name)).findFirst().orElseThrow()));
        clickOn("#openCompanyButton");
        waitFor(() -> lookup("#mainWindow").tryQuery().isPresent());
    }

    private void openFromNavigation(String title) {
        TreeView<String> navigation = lookup("#navigation").query();
        clickOn(navigation.lookupAll(".tree-cell").stream()
                .filter(cell -> title.equals(((TreeCell<?>) cell).getText())).findFirst().orElseThrow());
    }

    @Test
    @DisplayName("launches on the company picker")
    void launches() throws Exception {
        waitFor(() -> lookup("#pickerStatus").queryAs(Label.class).getText().startsWith("Ready"));
        assertThat(lookup("#companyList").tryQuery()).isPresent();
        assertThat(lookup("#createCompanyButton").tryQuery()).isPresent();
        assertThat(lookup("#openCompanyButton").tryQuery()).isPresent();
    }

    @Test
    @DisplayName("D-32: creates a company with a January year from its books' first date, and opens it in that year")
    void createsCompany() throws Exception {
        waitFor(() -> lookup("#pickerStatus").queryAs(Label.class).getText().startsWith("Ready"));
        clickOn("#companyName").write("Alpha Traders").type(KeyCode.ENTER);
        ComboBox<Month> month = lookup("#fiscalStartMonth").query();
        interact(() -> month.setValue(Month.JANUARY));
        DateField booksFrom = lookup("#booksFrom").query();
        clickOn(booksFrom).eraseText(booksFrom.getText().length()).write("15-06-2025").type(KeyCode.ENTER);

        openCompany("Alpha Traders");

        assertThat(lookup("#companyBar").queryAs(Label.class).getText()).isEqualTo("Alpha Traders   ·   FY 2025");
    }

    @Test
    @DisplayName("a company needs a name")
    void needsAName() throws Exception {
        waitFor(() -> lookup("#pickerStatus").queryAs(Label.class).getText().startsWith("Ready"));
        clickOn("#createCompanyButton");
        assertThat(lookup("#pickerStatus").queryAs(Label.class).getText()).isEqualTo("Enter a company name first.");
    }

    @Test
    @DisplayName("the application data directory is created on startup")
    void createsDataDirectory() throws Exception {
        waitFor(() -> lookup("#pickerStatus").queryAs(Label.class).getText().startsWith("Ready"));
        assertThat(Files.exists(dataDir.resolve("app.db"))).isTrue();
        assertThat(Files.isDirectory(dataDir.resolve("companies"))).isTrue();
    }

    @Test
    @DisplayName("P6-4: the trial balance opens from the navigation as a tab, showing the posted books balanced")
    void showsTrialBalanceOfPostedBooks() throws Exception {
        openCompany("Seeded Traders");

        openFromNavigation("Trial balance");
        waitFor(() -> lookup("#trialBalanceStatus").tryQuery()
                .map(node -> ((Label) node).getText().startsWith("Balanced")).orElse(false));

        TableView<TrialBalanceRow> table = lookup("#trialBalanceTable").queryTableView();
        assertThat(table.getItems()).extracting(TrialBalanceRow::ledgerName)
                .containsExactly("Cash in Hand", "Opening Balance Equity");
        assertThat(lookup("#totalDebit").queryAs(Label.class).getText()).isEqualTo("1,50,000.00");
        assertThat(lookup("#totalCredit").queryAs(Label.class).getText()).isEqualTo("1,50,000.00");
    }

    @Test
    @DisplayName("a screen opens once; Ctrl+W closes it; Change company goes back to the picker")
    void tabsAndChangingCompany() throws Exception {
        openCompany("Seeded Traders");
        TabPane tabs = lookup("#workArea").query();

        openFromNavigation("Trial balance");
        openFromNavigation("Trial balance");
        waitFor(() -> tabs.getTabs().size() == 1);
        assertThat(tabs.getTabs()).hasSize(1);

        press(KeyCode.CONTROL).press(KeyCode.W).release(KeyCode.W).release(KeyCode.CONTROL);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(tabs.getTabs()).isEmpty();

        clickOn("#changeCompanyButton");
        waitFor(() -> lookup("#companyPicker").tryQuery().isPresent());
        assertThat(lookup("#mainWindow").tryQuery()).isEmpty();
    }
}
