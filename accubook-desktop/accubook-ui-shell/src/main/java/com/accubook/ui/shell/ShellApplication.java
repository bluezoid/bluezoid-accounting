package com.accubook.ui.shell;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.app.company.Company;
import com.accubook.app.company.CompanyService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.util.List;

/**
 * The Phase 1 walking skeleton.
 *
 * <p>Deliberately minimal, but it exercises every risk the phase exists to
 * retire: JPMS wiring, JavaFX startup from a linked runtime, SQLite pragmas, the
 * money boundary, migration on open, per-company isolation, and the UI-thread
 * rule.
 *
 * <h2>The UI-thread rule</h2>
 * JavaFX has one UI thread. Blocking it on a query freezes the window, and on a
 * slow disk or a large company file that is very visible. Database work
 * therefore runs on a background {@link Task} and results are published back via
 * the JavaFX application thread. Establishing this in Phase 1 matters more than
 * it looks: every later screen copies whatever the first one did.
 */
public final class ShellApplication extends Application {

    /**
     * Supplies the application service.
     *
     * <p>Static because JavaFX instantiates {@link Application} subclasses
     * reflectively, so there is no constructor to inject through. The launcher
     * sets this before calling {@code launch}.
     */
    public interface CompanyServiceFactory {
        CompanyService create() throws Exception;
    }

    private static CompanyServiceFactory serviceFactory;

    /** Installed by the launcher before the UI starts. */
    public static void setServiceFactory(CompanyServiceFactory factory) {
        serviceFactory = factory;
    }

    private CompanyService companyService;

    private final ListView<String> companyList = new ListView<>();
    private final Label statusLabel = new Label("Starting...");
    private final Label totalLabel = new Label("Total: -");
    private final TextField companyNameField = new TextField();

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setTop(header());
        root.setCenter(companyList);
        root.setBottom(controls());

        stage.setTitle("AccuBook Desktop");
        stage.setScene(new Scene(root, 720, 520));
        stage.setOnCloseRequest(event -> shutdown());
        stage.show();

        startServiceInBackground();
    }

    private VBox header() {
        Label title = new Label("AccuBook Desktop");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        VBox box = new VBox(4, title, statusLabel, totalLabel);
        box.setPadding(new Insets(0, 0, 12, 0));
        return box;
    }

    private VBox controls() {
        companyNameField.setPromptText("New company name");
        Button createButton = new Button("Create company");
        createButton.setId("createCompanyButton");
        createButton.setOnAction(event -> createCompany(companyNameField.getText()));

        Button trialBalanceButton = new Button("Trial balance");
        trialBalanceButton.setId("trialBalanceButton");
        trialBalanceButton.setOnAction(event -> showTrialBalance());

        companyList.setId("companyList");
        companyList.getSelectionModel().selectedIndexProperty().addListener(
                (obs, oldIndex, newIndex) -> openSelected(newIndex.intValue()));

        HBox createRow = new HBox(8, companyNameField, createButton);
        HBox actionRow = new HBox(8, trialBalanceButton);
        VBox box = new VBox(8, createRow, actionRow);
        box.setPadding(new Insets(12, 0, 0, 0));
        return box;
    }

    /**
     * Open the installation off the UI thread; a first-run migration is not
     * instant.
     *
     * <p>The service is supplied by a factory installed by the launcher rather
     * than constructed here. The shell deliberately does not know where data
     * lives on disk - that is the composition root's business, and keeping it
     * out means the shell has no dependency on the persistence layer's paths.
     */
    private void startServiceInBackground() {
        runAsync("Opening application data", () -> {
            companyService = serviceFactory.create();
            return companyService.companies();
        }, companies -> {
            showCompanies(companies);
            status("Ready. " + companies.size() + " company(ies).");
        });
    }

    private void createCompany(String name) {
        if (name == null || name.isBlank()) {
            status("Enter a company name first.");
            return;
        }
        runAsync("Creating company", () -> {
            companyService.createCompany(name.strip());
            return companyService.companies();
        }, companies -> {
            companyNameField.clear();
            showCompanies(companies);
            status("Created '" + name.strip() + "'.");
        });
    }

    private void openSelected(int index) {
        if (index < 0) {
            return;
        }
        runAsync("Opening company", () -> {
            List<Company> companies = companyService.companies();
            Company company = companies.get(index);
            companyService.openCompany(company.id());
            return companyService.chartOfAccounts().size();
        }, ledgerCount -> {
            totalLabel.setText("Chart of accounts: " + ledgerCount + " ledgers");
            status("Company open.");
        });
    }

    /**
     * Open the trial balance for the open company (P6-4).
     *
     * <p>A window of its own, so it can stay open beside the shell. It reads
     * through {@link CompanyService#readOpenCompany}, which hands out a
     * connection that cannot write - the screen is read-only by construction,
     * not by the absence of buttons.
     */
    private void showTrialBalance() {
        if (companyService == null || companyService.currentCompany().isEmpty()) {
            status("Open a company first.");
            return;
        }
        String companyName = companyService.currentCompany().get().name();
        TrialBalanceView view = new TrialBalanceView(new TrialBalanceView.Source() {
            @Override
            public List<FiscalYear> fiscalYears() throws Exception {
                return companyService.readOpenCompany(TrialBalanceQuery::fiscalYears);
            }

            @Override
            public TrialBalance trialBalance(String fiscalYearId) throws Exception {
                return companyService.readOpenCompany(
                        connection -> TrialBalanceQuery.compute(connection, fiscalYearId));
            }
        }, LocalDate.now());
        Stage window = new Stage();
        window.setTitle("Trial balance - " + companyName);
        window.setScene(new Scene(view, 760, 560));
        window.show();
        status("Trial balance opened.");
    }

    private void showCompanies(List<Company> companies) {
        companyList.getItems().setAll(companies.stream()
                .map(c -> c.name() + "   (schema v" + c.schemaVersion() + ")")
                .toList());
    }

    private void status(String message) {
        statusLabel.setText(message);
    }

    /**
     * Run work off the UI thread and publish the result back on it.
     *
     * <p>The failure branch matters as much as the success branch: a database
     * error that only appeared in a log would leave the window looking fine and
     * silently wrong.
     */
    private <T> void runAsync(String what, ThrowingSupplier<T> work, java.util.function.Consumer<T> onSuccess) {
        status(what + "...");
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.get();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            status(what + " failed: " + (error == null ? "unknown error" : error.getMessage()));
        });
        Thread thread = new Thread(task, "accubook-" + what.toLowerCase().replace(' ', '-'));
        thread.setDaemon(true);
        thread.start();
    }

    private void shutdown() {
        if (companyService != null) {
            try {
                companyService.close();
            } catch (Exception e) {
                // Closing during shutdown; nothing useful left to do with a failure.
            }
        }
        Platform.exit();
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
