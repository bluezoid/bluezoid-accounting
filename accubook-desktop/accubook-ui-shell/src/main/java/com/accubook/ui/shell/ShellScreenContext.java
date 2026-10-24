package com.accubook.ui.shell;

import com.accubook.app.company.CompanyService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.tax.RulePack;
import com.accubook.ui.api.ScreenContext;
import com.accubook.ui.api.UiThreadCheckedTransactions;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** What one screen is given: the open company, through the guard, never on the UI thread. */
final class ShellScreenContext implements ScreenContext {

    private static final Logger LOG = Logger.getLogger(ShellScreenContext.class.getName());

    private final CompanyService service;
    private final EntitlementGuard guard;
    private final Clock clock;
    private final ModuleState state;
    private final Supplier<LocalDate> fiscalYearStart;
    private final Consumer<String> status;
    private final BiConsumer<String, Map<String, String>> opener;
    private final ModuleTransactions transactions;

    ShellScreenContext(CompanyService service, Clock clock, ModuleState state, Supplier<LocalDate> fiscalYearStart,
                       Consumer<String> status, BiConsumer<String, Map<String, String>> opener) {
        this.service = service;
        this.guard = new EntitlementGuard(service.moduleStates());
        this.clock = clock;
        this.state = state;
        this.fiscalYearStart = fiscalYearStart;
        this.status = status;
        this.opener = opener;
        this.transactions = UiThreadCheckedTransactions.wrap(service.openCompanyTransactions());
    }

    @Override
    public ModuleTransactions transactions() {
        return transactions;
    }

    @Override
    public EntitlementGuard guard() {
        return guard;
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public Supplier<RulePack> rules() {
        return service::rules;
    }

    @Override
    public ModuleState state() {
        return state;
    }

    @Override
    public LocalDate fiscalYearStart() {
        return fiscalYearStart.get();
    }

    /**
     * The failure branch matters as much as the success one: an engine refusal
     * ("the year is closed") is the message the person needs, shown as it is;
     * the detail goes to the log, never a stack trace on screen.
     */
    @Override
    public <T> void background(String what, Work<T> work, Consumer<T> done) {
        notify(what + "...");
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.run();
            }
        };
        task.setOnSucceeded(e -> {
            notify("");
            done.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            LOG.log(Level.WARNING, what + " failed", error);
            notify(what + " failed: " + message(error));
        });
        Thread thread = new Thread(task, "accubook-screen");
        thread.setDaemon(true);
        thread.start();
    }

    static String message(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        Throwable cause = error;
        while (cause.getMessage() == null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override
    public void open(String entryId, Map<String, String> arguments) {
        Platform.runLater(() -> opener.accept(entryId, arguments));
    }

    @Override
    public void notify(String message) {
        if (Platform.isFxApplicationThread()) {
            status.accept(message);
        } else {
            Platform.runLater(() -> status.accept(message));
        }
    }
}
