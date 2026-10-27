package com.accubook.integration.ui;

import com.accubook.app.company.CompanyService;
import com.accubook.inventory.InventoryModule;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.tax.RulePack;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.ScreenContext;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.UiThreadCheckedTransactions;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A feature's screen, built through its {@code ScreenProvider} as the shell
 * builds it, against a real company file (FY 2026-27, 10 May 2026), with a
 * context that behaves as the shell's does: guarded transactions refused on
 * the UI thread, work in the background, failures in the status bar.
 */
abstract class FeatureScreenITBase extends ApplicationTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);

    CompanyService service;
    Label status;

    abstract ScreenProvider provider();

    abstract String entryId();

    void seed() throws Exception {
    }

    @Override
    public void start(Stage stage) throws Exception {
        service = CompanyService.start(Files.createTempDirectory("accubook-feature-screen"),
                ModuleRegistry.discover(List.of(new InventoryModule())), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders", 4, LocalDate.of(2026, 4, 1)).id());
        seed();
        status = new Label();
        status.setId("statusBar");
        NavigationEntry entry = provider().entries().stream().filter(e -> e.id().equals(entryId())).findFirst()
                .orElseThrow();
        stage.setScene(new Scene(new BorderPane(entry.factory().apply(new Context()).view(), null, null, status,
                null), 1100, 800));
        stage.show();
    }

    @AfterEach
    void closeService() throws Exception {
        service.close();
    }

    void waitFor(Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, condition);
        WaitForAsyncUtils.waitForFxEvents();
    }

    void waitForText(String id, String prefix) throws Exception {
        waitFor(() -> lookup(id).queryAs(Label.class).getText().startsWith(prefix));
    }

    final class Context implements ScreenContext {

        private final ModuleTransactions transactions = UiThreadCheckedTransactions.wrap(
                service.openCompanyTransactions());

        @Override
        public ModuleTransactions transactions() {
            return transactions;
        }

        @Override
        public EntitlementGuard guard() {
            return new EntitlementGuard(service.moduleStates());
        }

        @Override
        public Clock clock() {
            return CLOCK;
        }

        @Override
        public Supplier<RulePack> rules() {
            return service::rules;
        }

        @Override
        public ModuleState state() {
            return ModuleState.ACTIVE;
        }

        @Override
        public LocalDate fiscalYearStart() {
            return LocalDate.of(2026, 4, 1);
        }

        @Override
        public <T> void background(String what, Work<T> work, Consumer<T> done) {
            Task<T> task = new Task<>() {
                @Override
                protected T call() throws Exception {
                    return work.run();
                }
            };
            task.setOnSucceeded(e -> done.accept(task.getValue()));
            task.setOnFailed(e -> status.setText(what + " failed: " + task.getException().getMessage()));
            Thread thread = new Thread(task, "accubook-test-screen");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void open(String entryId, Map<String, String> arguments) {
        }

        @Override
        public void notify(String message) {
            Platform.runLater(() -> status.setText(message));
        }
    }
}
