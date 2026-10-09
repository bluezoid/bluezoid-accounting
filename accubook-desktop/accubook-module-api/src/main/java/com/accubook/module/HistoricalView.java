package com.accubook.module;

import java.util.List;

/**
 * A read-only description of one of a module's record sets, for the generic
 * viewer shown when the module itself is not installed.
 *
 * <p>Pure metadata. It names a table and the columns worth showing; it carries no
 * behaviour, so the base build can render a downgraded customer's payroll history
 * without shipping payroll code.
 *
 * <p><strong>This is a read-only path by construction.</strong> There is nothing
 * here to express a write, so the viewer cannot become a way around entitlement
 * even if someone later wires it carelessly.
 *
 * @param title       shown to the user, e.g. "Payslips"
 * @param table       the table to read
 * @param columns     columns to display, in order
 * @param orderBy     optional ORDER BY column
 * @param descending  whether to sort descending - usually true, newest first
 */
public record HistoricalView(String title, String table, List<Column> columns,
                             String orderBy, boolean descending) {

    public HistoricalView {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Historical view needs a title");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("Historical view needs a table");
        }
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Historical view '" + title + "' has no columns");
        }
    }

    /**
     * One displayed column.
     *
     * @param name  the database column
     * @param label the heading shown to the user
     * @param kind  how to format it
     */
    public record Column(String name, String label, Kind kind) {

        public enum Kind {
            TEXT,
            /** Scaled integer minor units; rendered through the money mapper. */
            MONEY,
            /** ISO date stored as TEXT. */
            DATE,
            /** Epoch millis. */
            TIMESTAMP,
            NUMBER
        }
    }
}
