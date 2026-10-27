package com.accubook.ui.api;

import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.tax.RulePack;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Everything a screen may use. Given by the shell for the open company. */
public interface ScreenContext {

    /** The open company's guarded transactions. Refuses calls on the JavaFX thread. */
    ModuleTransactions transactions();

    EntitlementGuard guard();

    Clock clock();

    /** The rule pack in force. It may read storage: call it only inside {@link #background}. */
    Supplier<RulePack> rules();

    /** ACTIVE, or READ_ONLY: the screen opens with editing disabled. */
    ModuleState state();

    /** The first day of the fiscal year being worked in, for date entry. */
    LocalDate fiscalYearStart();

    /** Run work off the JavaFX thread; {@code done} runs on it; failures are shown. */
    <T> void background(String what, Work<T> work, Consumer<T> done);

    /** Open another screen, such as a document from a register. */
    void open(String entryId, Map<String, String> arguments);

    /** A message in the status bar. */
    void notify(String message);

    @FunctionalInterface
    interface Work<T> {
        T run() throws Exception;
    }
}
