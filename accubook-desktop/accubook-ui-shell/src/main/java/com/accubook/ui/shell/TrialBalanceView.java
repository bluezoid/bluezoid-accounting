package com.accubook.ui.shell;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.accounting.chart.TrialBalanceRow;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The read-only trial balance screen (P6-4).
 *
 * <p>Nothing on it can change the books: it has no editable cell and no action
 * beyond choosing a year, and its data comes from a source the shell backs with
 * a read connection that refuses writes.
 *
 * <p>Three things an accountant checks first are made impossible to miss:
 * <ul>
 *   <li>whether the columns agree - an out-of-balance trial balance is shown as
 *       a warning with the difference, never as two quietly different numbers;</li>
 *   <li>which year is shown - the report is fiscal-year scoped (P6-3);</li>
 *   <li>deactivated accounts that still hold a balance - they stay on the
 *       report (A-6), marked, rather than vanishing.</li>
 * </ul>
 *
 * <p>Loading runs off the JavaFX thread, as every screen must.
 */
public final class TrialBalanceView extends BorderPane {

    /** Where the view gets its data. Called off the JavaFX thread. */
    public interface Source {
        List<FiscalYear> fiscalYears() throws Exception;

        TrialBalance trialBalance(String fiscalYearId) throws Exception;
    }

    private final Source source;
    private final LocalDate today;

    private final ComboBox<FiscalYear> yearPicker = new ComboBox<>();
    private final CheckBox showZeroRows = new CheckBox("Show accounts with no balance");
    private final TableView<TrialBalanceRow> table = new TableView<>();
    private final Label totalDebit = new Label();
    private final Label totalCredit = new Label();
    private final Label status = new Label("Loading...");

    private TrialBalance current;

    public TrialBalanceView(Source source, LocalDate today) {
        this.source = Objects.requireNonNull(source, "source");
        this.today = Objects.requireNonNull(today, "today");
        setId("trialBalanceView");
        setPadding(new Insets(16));

        yearPicker.setId("fiscalYearPicker");
        yearPicker.setCellFactory(list -> new YearCell());
        yearPicker.setButtonCell(new YearCell());
        yearPicker.setOnAction(event -> {
            FiscalYear year = yearPicker.getValue();
            if (year != null) {
                loadTrialBalance(year);
            }
        });

        showZeroRows.setId("showZeroRows");
        showZeroRows.setOnAction(event -> showRows());

        HBox top = new HBox(12, new Label("Fiscal year"), yearPicker, showZeroRows);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 0, 12, 0));
        setTop(top);

        table.setId("trialBalanceTable");
        table.setEditable(false);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No postings in this year."));
        table.getColumns().add(column("Account", "accountColumn", row ->
                row.active() ? row.ledgerName() : row.ledgerName() + "  (inactive)"));
        table.getColumns().add(column("Nature", "natureColumn", row -> row.nature().name()));
        table.getColumns().add(amountColumn("Debit", "debitColumn", row -> AmountFormat.format(row.debit())));
        table.getColumns().add(amountColumn("Credit", "creditColumn", row -> AmountFormat.format(row.credit())));
        setCenter(table);

        totalDebit.setId("totalDebit");
        totalCredit.setId("totalCredit");
        status.setId("trialBalanceStatus");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox totals = new HBox(24, new Label("Total"), spacer,
                new Label("Debit"), totalDebit, new Label("Credit"), totalCredit);
        totals.setAlignment(Pos.CENTER_RIGHT);
        totals.setStyle("-fx-font-weight: bold;");
        VBox bottom = new VBox(8, totals, status);
        bottom.setPadding(new Insets(12, 0, 0, 0));
        setBottom(bottom);

        loadYears();
    }

    private void loadYears() {
        runAsync("Reading fiscal years", source::fiscalYears, years -> {
            yearPicker.getItems().setAll(years);
            TrialBalanceQuery.defaultYear(years, today).ifPresentOrElse(
                    yearPicker::setValue,   // fires the picker's action, which loads it
                    () -> setStatus("This company has no fiscal year.", false));
        });
    }

    private void loadTrialBalance(FiscalYear year) {
        runAsync("Computing trial balance", () -> source.trialBalance(year.id()), balance -> {
            current = balance;
            showRows();
            totalDebit.setText(AmountFormat.format(balance.totalDebit()));
            totalCredit.setText(AmountFormat.format(balance.totalCredit()));
            if (balance.balances()) {
                setStatus("Balanced - " + year.name()
                        + (year.closed() ? " (closed)" : ""), false);
            } else {
                // Should never happen: the posting core refuses an unbalanced
                // voucher. If it does, the books are damaged and the person
                // reading this must not mistake it for a normal report.
                setStatus("OUT OF BALANCE by " + AmountFormat.format(balance.difference())
                        + ". The books are inconsistent; do not rely on this report.", true);
            }
        });
    }

    private void showRows() {
        if (current == null) {
            return;
        }
        table.getItems().setAll(showZeroRows.isSelected() ? current.rows() : current.nonZeroRows());
    }

    private void setStatus(String message, boolean warning) {
        status.setText(message);
        status.setStyle(warning ? "-fx-text-fill: #b00020; -fx-font-weight: bold;" : "");
    }

    private <T> void runAsync(String what, ShellApplication.ThrowingSupplier<T> work,
                              java.util.function.Consumer<T> onSuccess) {
        setStatus(what + "...", false);
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.get();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            setStatus(what + " failed: " + (error == null ? "unknown error" : error.getMessage()), true);
        });
        Thread thread = new Thread(task, "accubook-trial-balance");
        thread.setDaemon(true);
        thread.start();
    }

    private static TableColumn<TrialBalanceRow, String> column(
            String title, String id, Function<TrialBalanceRow, String> value) {
        TableColumn<TrialBalanceRow, String> column = new TableColumn<>(title);
        column.setId(id);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private static TableColumn<TrialBalanceRow, String> amountColumn(
            String title, String id, Function<TrialBalanceRow, String> value) {
        TableColumn<TrialBalanceRow, String> column = column(title, id, value);
        column.setStyle("-fx-alignment: CENTER-RIGHT;");
        return column;
    }

    private static final class YearCell extends ListCell<FiscalYear> {
        @Override
        protected void updateItem(FiscalYear year, boolean empty) {
            super.updateItem(year, empty);
            setText(empty || year == null ? null
                    : year.name() + "  (" + year.startDate() + " to " + year.endDate() + ")");
        }
    }
}
