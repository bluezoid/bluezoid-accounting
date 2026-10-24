package com.accubook.ui.shell;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.app.company.Company;
import com.accubook.app.company.CompanyService;
import com.accubook.ui.api.ScreenProvider;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The application: a company picker, then the main window for the open
 * company, whose screens come from the kernel and from whichever feature
 * modules this build contains (Phase 11S).
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
    private Stage stage;
    private CompanyPicker picker;
    private ScreenCatalogue catalogue;
    private final Clock clock = Clock.systemDefaultZone();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        picker = new CompanyPicker(() -> LocalDate.now(clock), this::openCompany, this::createCompany);
        stage.setTitle("AccuBook Desktop");
        stage.setScene(new Scene(picker, 1100, 720));
        stage.setOnCloseRequest(event -> shutdown());
        stage.show();

        startServiceInBackground();
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
            catalogue = ScreenCatalogue.of(providers(), companyService.moduleStates());
            return companyService.companies();
        }, companies -> {
            picker.show(companies);
            status(companies.isEmpty() ? "Ready. Create your first company below."
                    : "Ready. " + companies.size() + " company(ies).");
        });
    }

    /** The kernel's screens and every module's present in this build - never named here. */
    private static List<ScreenProvider> providers() {
        List<ScreenProvider> providers = new ArrayList<>();
        providers.add(new KernelScreens());
        ServiceLoader.load(ScreenProvider.class).forEach(providers::add);
        return providers;
    }

    private void createCompany(CompanyPicker.NewCompany request) {
        runAsync("Creating company", () -> {
            companyService.createCompany(request.name(), request.fiscalYearStartMonth(), request.booksFrom());
            return companyService.companies();
        }, companies -> {
            picker.clearForm();
            picker.show(companies);
            status("Created '" + request.name() + "'. Select it and press Open.");
        });
    }

    /** Open a company and its working year, then show the main window. */
    private void openCompany(Company company) {
        runAsync("Opening " + company.name(), () -> {
            companyService.openCompany(company.id());
            return companyService.readOpenCompany(TrialBalanceQuery::fiscalYears);
        }, years -> {
            FiscalYear year = MainWindow.workingYear(years, LocalDate.now(clock));
            MainWindow window = new MainWindow(companyService, catalogue, clock, year, this::showPicker);
            stage.getScene().setRoot(window);
            window.installShortcuts(stage.getScene());
            stage.setTitle("AccuBook Desktop - " + company.name());
        });
    }

    private void showPicker() {
        stage.getScene().getAccelerators().clear();
        stage.getScene().setRoot(picker);
        stage.setTitle("AccuBook Desktop");
        runAsync("Listing companies", () -> companyService.companies(), list -> {
            picker.show(list);
            status("Ready.");
        });
    }

    private void status(String message) {
        picker.status(message);
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
