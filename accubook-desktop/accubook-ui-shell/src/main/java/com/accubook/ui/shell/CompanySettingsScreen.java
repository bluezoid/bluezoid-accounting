package com.accubook.ui.shell;

import com.accubook.tax.CompanyIdentity;
import com.accubook.tax.CompositionCategory;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.StateCode;
import com.accubook.ui.api.DateField;
import com.accubook.ui.api.DateInput;
import com.accubook.ui.api.Keyboard;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenContext;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * The company's legal identity and its GST registrations (P9-8, P9e). A
 * registration is never edited: a change of scheme is a new registration from
 * a date, so documents already posted keep the basis they were taxed on.
 */
final class CompanySettingsScreen implements Screen {

    private final ScreenContext context;
    private final CompanyIdentity identity;
    private final GstRegistrations registrations;

    private final TextField legalName = new TextField();
    private final TextField line1 = new TextField();
    private final TextField city = new TextField();
    private final ComboBox<StateCode> state = new ComboBox<>();
    private final TextField pin = new TextField();
    private final Button saveIdentity = new Button("Save");

    private final TableView<GstRegistration> history = new TableView<>();
    private final DateField from;
    private final ComboBox<GstRegistration.Scheme> scheme = new ComboBox<>();
    private final TextField gstin = new TextField();
    private final ComboBox<StateCode> registrationState = new ComboBox<>();
    private final ComboBox<CompositionCategory> category = new ComboBox<>();
    private final Button register = new Button("Register");
    private final Label message = new Label();
    private final ScrollPane view;

    CompanySettingsScreen(ScreenContext context) {
        this.context = context;
        this.identity = new CompanyIdentity(context.transactions());
        this.registrations = new GstRegistrations(context.transactions(), context.clock());
        this.from = new DateField(() -> LocalDate.now(context.clock()), context::fiscalYearStart);

        for (ComboBox<StateCode> box : List.of(state, registrationState)) {
            box.setConverter(new StringConverter<>() {
                @Override
                public String toString(StateCode s) {
                    return s == null ? "" : s.code() + " " + s.name();
                }

                @Override
                public StateCode fromString(String text) {
                    return null;
                }
            });
        }
        legalName.setId("legalName");
        line1.setId("addressLine1");
        city.setId("city");
        state.setId("state");
        pin.setId("pin");
        saveIdentity.setId("saveIdentity");
        saveIdentity.setOnAction(e -> saveIdentity());
        Keyboard.enterAdvances(List.of(legalName, line1, city, state, pin), saveIdentity);

        GridPane address = form();
        address.addRow(0, new Label("Legal name"), legalName);
        address.addRow(1, new Label("Address"), line1);
        address.addRow(2, new Label("City"), city);
        address.addRow(3, new Label("State"), state);
        address.addRow(4, new Label("PIN code"), pin);
        address.add(saveIdentity, 1, 5);

        history.setId("registrationHistory");
        history.setPrefHeight(150);
        history.setPlaceholder(new Label("No GST registration yet: documents cannot be posted until one is recorded."));
        history.getColumns().add(column("From", r -> DateInput.format(r.effectiveFrom())));
        history.getColumns().add(column("Scheme", r -> r.scheme().name()));
        history.getColumns().add(column("GSTIN", r -> r.gstin() == null ? "" : r.gstin()));
        history.getColumns().add(column("State", GstRegistration::stateCode));
        history.getColumns().add(column("Category",
                r -> r.compositionCategory() == null ? "" : r.compositionCategory().name()));

        from.setId("registrationFrom");
        scheme.setId("scheme");
        scheme.getItems().setAll(GstRegistration.Scheme.values());
        scheme.setValue(GstRegistration.Scheme.REGULAR);
        gstin.setId("gstin");
        gstin.setPromptText("15 characters");
        registrationState.setId("registrationState");
        category.setId("compositionCategory");
        category.getItems().setAll(CompositionCategory.values());
        category.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(CompositionCategory item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name());
            }
        });
        gstin.disableProperty().bind(scheme.valueProperty().isEqualTo(GstRegistration.Scheme.UNREGISTERED));
        category.disableProperty().bind(scheme.valueProperty().isNotEqualTo(GstRegistration.Scheme.COMPOSITION));
        register.setId("register");
        register.setOnAction(e -> register());
        Keyboard.enterAdvances(List.of(from, scheme, gstin, registrationState, category), register);

        GridPane registration = form();
        registration.addRow(0, new Label("Effective from"), from);
        registration.addRow(1, new Label("Scheme"), scheme);
        registration.addRow(2, new Label("GSTIN"), gstin);
        registration.addRow(3, new Label("State"), registrationState);
        registration.addRow(4, new Label("Composition category"), category);
        registration.add(register, 1, 5);

        message.setId("settingsMessage");
        message.setWrapText(true);
        if (!context.state().allowsMutation()) {
            saveIdentity.setDisable(true);
            register.setDisable(true);
        }
        VBox content = new VBox(12, heading("Company"), address, heading("GST registrations"), history,
                heading("New registration"), registration, message);
        content.setPadding(new Insets(16));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
        view.setId("companySettings");
        load();
    }

    @Override
    public Node view() {
        return view;
    }

    private void load() {
        context.background("Reading company settings",
                () -> new Loaded(context.rules().get().states(), identity.get().orElse(null),
                        registrations.history()), loaded -> {
                    state.getItems().setAll(loaded.states());
                    registrationState.getItems().setAll(loaded.states());
                    if (loaded.address() != null) {
                        legalName.setText(loaded.address().legalName());
                        line1.setText(loaded.address().line1());
                        city.setText(loaded.address().city());
                        state.setValue(byCode(loaded.address().stateCode()));
                        pin.setText(loaded.address().pin());
                    }
                    history.getItems().setAll(loaded.history());
                    if (registrationState.getValue() == null) {
                        registrationState.setValue(state.getValue());
                    }
                });
    }

    private record Loaded(List<StateCode> states, CompanyIdentity.Address address, List<GstRegistration> history) {
    }

    private void saveIdentity() {
        String code = state.getValue() == null ? null : state.getValue().code();
        CompanyIdentity.Address address = new CompanyIdentity.Address(legalName.getText(), line1.getText(),
                city.getText(), code, pin.getText());
        context.background("Saving the company's address", () -> {
            identity.set(address, context.rules().get());
            return null;
        }, done -> {
            say("Company address saved.");
            if (registrationState.getValue() == null) {
                registrationState.setValue(state.getValue());
            }
        });
    }

    private void register() {
        if (!from.commit() || from.getValue() == null) {
            say("Enter the date the registration takes effect.");
            return;
        }
        GstRegistration.Scheme chosen = scheme.getValue();
        String number = chosen == GstRegistration.Scheme.UNREGISTERED ? null : gstin.getText().strip();
        String code = registrationState.getValue() == null ? null : registrationState.getValue().code();
        CompositionCategory composition = chosen == GstRegistration.Scheme.COMPOSITION ? category.getValue() : null;
        LocalDate effective = from.getValue();
        context.background("Recording the GST registration", () -> {
            registrations.register(chosen, number, code, effective, context.rules().get(), composition);
            return registrations.history();
        }, list -> {
            history.getItems().setAll(list);
            gstin.clear();
            say("Registered " + chosen.name().toLowerCase() + " from " + DateInput.format(effective) + ".");
        });
    }

    private void say(String text) {
        message.setText(text);
        context.notify(text);
    }

    private StateCode byCode(String code) {
        return state.getItems().stream().filter(s -> s.code().equals(code)).findFirst().orElse(null);
    }

    private static GridPane form() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        return grid;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        return label;
    }

    private static TableColumn<GstRegistration, String> column(String title, Function<GstRegistration, String> value) {
        TableColumn<GstRegistration, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }
}
