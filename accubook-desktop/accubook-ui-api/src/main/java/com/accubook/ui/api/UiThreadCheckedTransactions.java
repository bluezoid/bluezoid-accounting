package com.accubook.ui.api;

import com.accubook.module.ModuleTransactions;
import javafx.application.Platform;

import java.sql.SQLException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The architecture rule "the UI thread never touches the database", enforced:
 * every call made on the JavaFX thread is refused. A screen that forgets to use
 * {@link ScreenContext#background} fails the first time it is tried, not when a
 * customer's large company file freezes the window.
 */
public final class UiThreadCheckedTransactions implements ModuleTransactions {

    private final ModuleTransactions delegate;
    private final BooleanSupplier onUiThread;

    UiThreadCheckedTransactions(ModuleTransactions delegate, BooleanSupplier onUiThread) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.onUiThread = onUiThread;
    }

    public static ModuleTransactions wrap(ModuleTransactions delegate) {
        return new UiThreadCheckedTransactions(delegate, Platform::isFxApplicationThread);
    }

    @Override
    public <T> T inTransaction(Work<T> work) throws SQLException {
        refuseOnUiThread();
        return delegate.inTransaction(work);
    }

    @Override
    public <T> T read(Work<T> work) throws SQLException {
        refuseOnUiThread();
        return delegate.read(work);
    }

    private void refuseOnUiThread() {
        if (onUiThread.getAsBoolean()) {
            throw new IllegalStateException("Database work on the JavaFX thread; use ScreenContext.background");
        }
    }
}
