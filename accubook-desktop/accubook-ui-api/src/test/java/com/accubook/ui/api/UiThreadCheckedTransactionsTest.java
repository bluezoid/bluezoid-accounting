package com.accubook.ui.api;

import com.accubook.module.ModuleTransactions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The UI thread never touches the database - refused, not merely advised. */
class UiThreadCheckedTransactionsTest {

    private final AtomicInteger calls = new AtomicInteger();
    private final ModuleTransactions database = new ModuleTransactions() {
        @Override
        public <T> T inTransaction(Work<T> work) throws java.sql.SQLException {
            calls.incrementAndGet();
            return work.execute((Connection) null);
        }

        @Override
        public <T> T read(Work<T> work) throws java.sql.SQLException {
            calls.incrementAndGet();
            return work.execute((Connection) null);
        }
    };

    @Test
    @DisplayName("on the UI thread, reads and writes are refused before reaching the database")
    void refusedOnUiThread() {
        ModuleTransactions checked = new UiThreadCheckedTransactions(database, () -> true);

        assertThrows(IllegalStateException.class, () -> checked.read(c -> 1));
        assertThrows(IllegalStateException.class, () -> checked.inTransaction(c -> 1));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("off it, both pass through")
    void passesOffUiThread() throws Exception {
        ModuleTransactions checked = new UiThreadCheckedTransactions(database, () -> false);

        assertEquals(1, (int) checked.read(c -> 1));
        assertEquals(2, (int) checked.inTransaction(c -> 2));
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("the real check: a plain test thread is not the JavaFX thread")
    void realCheck() throws Exception {
        assertEquals(3, (int) UiThreadCheckedTransactions.wrap(database).read(c -> 3));
    }
}
