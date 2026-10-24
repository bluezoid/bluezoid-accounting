package com.accubook.accounting.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A report as rows and columns, ready to show or export. Cells are
 * {@link String}, {@link BigDecimal} (money, exact) or {@link LocalDate}; null
 * for empty.
 */
public record Table(String title, List<Column> columns, List<List<Object>> rows) {

    public enum Kind { TEXT, MONEY, DATE }

    public record Column(String name, Kind kind) {
    }

    public Table {
        columns = List.copyOf(columns);
        rows = rows.stream().map(r -> java.util.Collections.unmodifiableList(new java.util.ArrayList<>(r))).toList();
        for (List<Object> row : rows) {
            if (row.size() != columns.size()) {
                throw new IllegalArgumentException("A row has " + row.size() + " cells for " + columns.size()
                        + " columns");
            }
        }
    }
}
