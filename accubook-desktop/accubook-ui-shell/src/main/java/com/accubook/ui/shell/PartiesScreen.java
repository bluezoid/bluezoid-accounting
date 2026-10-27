package com.accubook.ui.shell;

import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyProfile;
import com.accubook.accounting.party.PartyRole;
import com.accubook.tax.Gstin;
import com.accubook.tax.Pan;
import com.accubook.tax.RulePack;
import com.accubook.tax.StateCode;
import com.accubook.ui.api.AmountField;
import com.accubook.ui.api.Keyboard;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenContext;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Customers and vendors: each has its own ledger in Sundry Debtors or Sundry
 * Creditors (P7-2). A GSTIN is checked - format, check character, state - and
 * must be from the party's state; a PAN is checked too. A party is
 * deactivated, never deleted once it has a history.
 */
final class PartiesScreen implements Screen {

    private final ScreenContext context;
    private final Parties parties;
    private final TableView<Party> table = new TableView<>();
    private final TextField name = new TextField();
    private final ComboBox<PartyRole> role = new ComboBox<>();
    private final TextField gstin = new TextField();
    private final ComboBox<StateCode> state = new ComboBox<>();
    private final TextField pan = new TextField();
    private final TextField phone = new TextField();
    private final TextField email = new TextField();
    private final TextField address = new TextField();
    private final TextField city = new TextField();
    private final TextField pin = new TextField();
    private final AmountField creditLimit = AmountField.money();
    private final TextField creditDays = new TextField();
    private final Button saveNew = new Button("Add party");
    private final Button saveChanges = new Button("Save changes");
    private final Button toggle = new Button("Deactivate");
    private final Button clear = new Button("New");
    private final Label message = new Label();
    private final BorderPane view = new BorderPane();
    private RulePack rules;

    PartiesScreen(ScreenContext context) {
        this.context = context;
        this.parties = new Parties(context.transactions());

        table.setId("partyTable");
        table.setPlaceholder(new Label("No customers or vendors yet."));
        table.getColumns().add(column("Name", Party::name, 220));
        table.getColumns().add(column("Role", p -> label(p.role()), 90));
        table.getColumns().add(column("GSTIN", p -> nz(p.profile().gstin()), 150));
        table.getColumns().add(column("City", p -> nz(p.profile().city()), 110));
        table.getColumns().add(column("Phone", p -> nz(p.profile().phone()), 110));
        table.getColumns().add(column("Status", p -> p.active() ? "" : "Inactive", 70));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, p) -> show(p));

        name.setId("partyName");
        role.setId("partyRole");
        role.getItems().setAll(PartyRole.values());
        role.setValue(PartyRole.CUSTOMER);
        role.setConverter(new StringConverter<>() {
            @Override
            public String toString(PartyRole r) {
                return r == null ? "" : label(r);
            }

            @Override
            public PartyRole fromString(String s) {
                return null;
            }
        });
        gstin.setId("partyGstin");
        gstin.setPromptText("15 characters, if registered");
        state.setId("partyState");
        state.setConverter(new StringConverter<>() {
            @Override
            public String toString(StateCode s) {
                return s == null ? "" : s.code() + " " + s.name();
            }

            @Override
            public StateCode fromString(String text) {
                return null;
            }
        });
        // A GSTIN names its state: fill it in rather than ask twice.
        gstin.focusedProperty().addListener((obs, was, is) -> {
            if (!is && gstin.getText().strip().length() >= 2) {
                String code = gstin.getText().strip().substring(0, 2);
                state.getItems().stream().filter(s -> s.code().equals(code)).findFirst().ifPresent(state::setValue);
            }
        });
        pan.setId("partyPan");
        phone.setId("partyPhone");
        email.setId("partyEmail");
        address.setId("partyAddress");
        city.setId("partyCity");
        pin.setId("partyPin");
        creditLimit.setId("creditLimit");
        creditDays.setId("creditDays");
        saveNew.setId("addParty");
        saveChanges.setId("saveParty");
        toggle.setId("toggleParty");
        clear.setId("newParty");
        message.setId("partyMessage");
        message.setWrapText(true);
        saveNew.setOnAction(e -> save(null));
        saveChanges.setOnAction(e -> {
            Party p = table.getSelectionModel().getSelectedItem();
            if (p != null) {
                save(p);
            }
        });
        toggle.setOnAction(e -> toggle());
        clear.setOnAction(e -> {
            table.getSelectionModel().clearSelection();
            show(null);
            name.requestFocus();
        });
        Keyboard.enterAdvances(List.of(name, role, gstin, state, pan, phone, email, address, city, pin, creditLimit,
                creditDays), saveNew);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        int row = 0;
        form.addRow(row++, new Label("Name"), name);
        form.addRow(row++, new Label("Role"), role);
        form.addRow(row++, new Label("GSTIN"), gstin);
        form.addRow(row++, new Label("State"), state);
        form.addRow(row++, new Label("PAN"), pan);
        form.addRow(row++, new Label("Phone"), phone);
        form.addRow(row++, new Label("Email"), email);
        form.addRow(row++, new Label("Address"), address);
        form.addRow(row++, new Label("City"), city);
        form.addRow(row++, new Label("PIN code"), pin);
        form.addRow(row++, new Label("Credit limit"), creditLimit);
        form.addRow(row++, new Label("Credit days"), creditDays);
        HBox buttons = new HBox(8, saveNew, saveChanges, toggle, clear);
        VBox side = new VBox(12, form, buttons, message);
        side.setPadding(new Insets(0, 0, 0, 16));
        ScrollPane sideScroll = new ScrollPane(side);
        sideScroll.setFitToWidth(true);
        sideScroll.setPrefWidth(470);

        if (!context.state().allowsMutation()) {
            for (Button b : List.of(saveNew, saveChanges, toggle)) {
                b.setDisable(true);
            }
        }
        view.setId("partiesScreen");
        view.setPadding(new Insets(16));
        view.setCenter(table);
        view.setRight(sideScroll);
        show(null);
        load(null);
    }

    @Override
    public Node view() {
        return view;
    }

    @Override
    public void shown() {
        Party p = table.getSelectionModel().getSelectedItem();
        load(p == null ? null : p.id());
    }

    private record Loaded(RulePack rules, List<Party> parties) {
    }

    private void load(String keepId) {
        context.background("Reading parties", () -> new Loaded(context.rules().get(), parties.all()), loaded -> {
            rules = loaded.rules();
            if (state.getItems().isEmpty()) {
                state.getItems().setAll(rules.states());
            }
            table.getItems().setAll(loaded.parties());
            if (keepId != null) {
                table.getItems().stream().filter(p -> p.id().equals(keepId)).findFirst()
                        .ifPresent(p -> table.getSelectionModel().select(p));
            }
        });
    }

    private void show(Party p) {
        boolean editable = context.state().allowsMutation();
        saveChanges.setDisable(!editable || p == null);
        toggle.setDisable(!editable || p == null);
        toggle.setText(p != null && !p.active() ? "Reactivate" : "Deactivate");
        PartyProfile pr = p == null ? PartyProfile.empty() : p.profile();
        name.setText(p == null ? "" : p.name());
        role.setValue(p == null ? PartyRole.CUSTOMER : p.role());
        gstin.setText(nz(pr.gstin()));
        state.setValue(state.getItems().stream().filter(s -> s.code().equals(pr.stateCode())).findFirst()
                .orElse(null));
        pan.setText(nz(pr.pan()));
        phone.setText(nz(pr.phone()));
        email.setText(nz(pr.email()));
        address.setText(nz(pr.addressLine1()));
        city.setText(nz(pr.city()));
        pin.setText(nz(pr.postalCode()));
        creditLimit.setValue(pr.creditLimit());
        creditDays.setText(pr.creditDays() == null ? "" : pr.creditDays().toString());
    }

    /** The form as a profile, or a list of what is wrong with it. */
    private List<String> problems(PartyProfile[] out) {
        List<String> problems = new ArrayList<>();
        if (name.getText().isBlank()) {
            problems.add("A party needs a name");
        }
        String g = gstin.getText().strip();
        String code = state.getValue() == null ? null : state.getValue().code();
        if (!g.isEmpty()) {
            if (rules == null) {
                problems.add("The rule pack is still loading; try again");
            } else {
                Gstin.problem(g, rules).ifPresent(problems::add);
                if (code != null && Gstin.problem(g, rules).isEmpty() && !Gstin.stateOf(g).equals(code)) {
                    problems.add("The GSTIN is from state " + Gstin.stateOf(g) + ", not " + code);
                }
            }
        }
        String p = pan.getText().strip();
        if (!p.isEmpty()) {
            Pan.problem(p).ifPresent(problems::add);
            if (!g.isEmpty() && rules != null && Gstin.problem(g, rules).isEmpty()
                    && !Gstin.panOf(g).equalsIgnoreCase(p)) {
                problems.add("The PAN does not match the one inside the GSTIN");
            }
        }
        Integer days = null;
        if (!creditDays.getText().isBlank()) {
            try {
                days = Integer.valueOf(creditDays.getText().strip());
            } catch (NumberFormatException e) {
                problems.add("Credit days is a whole number of days");
            }
        }
        if (!creditLimit.commit()) {
            problems.add(creditLimit.errorProperty().get());
        }
        if (problems.isEmpty()) {
            out[0] = new PartyProfile(null, null, email.getText(), phone.getText(), address.getText(), city.getText(),
                    // A GSTIN carries the PAN, so a registered party's PAN is never left out.
                    code, pin.getText(), g, p.isEmpty() && !g.isEmpty() ? Gstin.panOf(g) : p, creditLimit.getValue(),
                    days);
        }
        return problems;
    }

    private void save(Party existing) {
        PartyProfile[] profile = {null};
        List<String> problems = problems(profile);
        if (!problems.isEmpty()) {
            say(String.join("; ", problems) + ".");
            return;
        }
        String newName = name.getText().strip();
        PartyRole newRole = role.getValue();
        if (existing == null) {
            context.background("Adding the party", () -> parties.create(newName, newRole, profile[0], now()), p -> {
                say("Added " + p.name() + ".");
                show(null);
                load(null);
            });
            return;
        }
        context.background("Saving the party", () -> {
            if (!existing.name().equals(newName)) {
                parties.rename(existing.id(), newName, now());
            }
            if (existing.role() != newRole) {
                parties.changeRole(existing.id(), newRole, now());
            }
            parties.updateProfile(existing.id(), profile[0], now());
            return existing.id();
        }, id -> {
            say("Saved " + newName + ".");
            load(id);
        });
    }

    private void toggle() {
        Party p = table.getSelectionModel().getSelectedItem();
        if (p == null) {
            return;
        }
        context.background(p.active() ? "Deactivating the party" : "Reactivating the party", () -> {
            if (p.active()) {
                parties.deactivate(p.id(), now());
            } else {
                parties.reactivate(p.id(), now());
            }
            return p.id();
        }, id -> {
            say(p.name() + (p.active() ? " deactivated; its history stays." : " reactivated."));
            load(id);
        });
    }

    private static String label(PartyRole role) {
        return switch (role) {
            case CUSTOMER -> "Customer";
            case VENDOR -> "Vendor";
            case BOTH -> "Both";
        };
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private long now() {
        return context.clock().millis();
    }

    private void say(String text) {
        message.setText(Objects.requireNonNullElse(text, ""));
        context.notify(text);
    }

    private static TableColumn<Party, String> column(String title, Function<Party, String> value, double width) {
        TableColumn<Party, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }
}
