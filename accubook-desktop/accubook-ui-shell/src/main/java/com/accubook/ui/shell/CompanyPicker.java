package com.accubook.ui.shell;

import com.accubook.app.company.Company;
import com.accubook.ui.api.DateField;
import com.accubook.ui.api.Keyboard;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Choose a company to open, or create one with its fiscal year (D-32). */
final class CompanyPicker extends VBox {

    /** What the form asks for a new company. */
    record NewCompany(String name, int fiscalYearStartMonth, LocalDate booksFrom) {
    }

    private final ListView<Company> companies = new ListView<>();
    private final TextField name = new TextField();
    private final ComboBox<Month> startMonth = new ComboBox<>();
    private final DateField booksFrom;
    private final Label status = new Label("Starting...");

    CompanyPicker(Supplier<LocalDate> today, Consumer<Company> open, Consumer<NewCompany> create) {
        super(12);
        setId("companyPicker");
        setPadding(new Insets(16));

        Label title = new Label("AccuBook Desktop");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        status.setId("pickerStatus");

        companies.setId("companyList");
        companies.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Company company, boolean empty) {
                super.updateItem(company, empty);
                setText(empty || company == null ? null : company.name());
            }
        });
        Button openButton = new Button("Open");
        openButton.setId("openCompanyButton");
        openButton.disableProperty().bind(companies.getSelectionModel().selectedItemProperty().isNull());
        openButton.setOnAction(e -> open.accept(companies.getSelectionModel().getSelectedItem()));
        companies.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && companies.getSelectionModel().getSelectedItem() != null) {
                open.accept(companies.getSelectionModel().getSelectedItem());
            }
        });
        companies.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && companies.getSelectionModel().getSelectedItem() != null) {
                open.accept(companies.getSelectionModel().getSelectedItem());
            }
        });

        name.setId("companyName");
        name.setPromptText("Company name");
        startMonth.setId("fiscalStartMonth");
        startMonth.getItems().setAll(Month.values());
        startMonth.setValue(Month.APRIL);
        startMonth.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Month m) {
                return m == null ? "" : m.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            }

            @Override
            public Month fromString(String s) {
                return Month.valueOf(s.toUpperCase(Locale.ROOT));
            }
        });
        booksFrom = new DateField(today, () -> LocalDate.of(today.get().getYear(), startMonth.getValue(), 1));
        booksFrom.setId("booksFrom");
        booksFrom.setValue(today.get());
        Button createButton = new Button("Create company");
        createButton.setId("createCompanyButton");
        createButton.setOnAction(e -> {
            if (name.getText().isBlank()) {
                status("Enter a company name first.");
                name.requestFocus();
            } else if (booksFrom.commit() && booksFrom.getValue() != null) {
                create.accept(new NewCompany(name.getText().strip(), startMonth.getValue().getValue(),
                        booksFrom.getValue()));
            } else {
                status("Enter the date your books begin, as dd-mm-yyyy.");
            }
        });
        Keyboard.enterAdvances(List.of(name, startMonth, booksFrom), createButton);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Name"), name);
        form.addRow(1, new Label("Fiscal year starts in"), startMonth);
        form.addRow(2, new Label("Books begin on"), booksFrom);
        form.add(createButton, 1, 3);

        getChildren().addAll(title, status, new Label("Companies"), companies, new HBox(8, openButton),
                new Label("New company"), form);
    }

    void show(List<Company> list) {
        companies.getItems().setAll(list);
        if (!list.isEmpty()) {
            companies.getSelectionModel().selectFirst();
        }
    }

    void status(String message) {
        status.setText(message);
    }

    void clearForm() {
        name.clear();
    }
}
