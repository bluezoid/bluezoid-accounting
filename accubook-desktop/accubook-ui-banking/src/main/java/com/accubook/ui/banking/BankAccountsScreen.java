package com.accubook.ui.banking;

import com.accubook.banking.BankAccount;
import com.accubook.banking.Banking;
import com.accubook.ui.api.Keyboard;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenContext;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Function;

/**
 * Bank accounts: each a ledger in Cash &amp; Bank with its bank's details,
 * opened together so a refused detail never leaves a ledger behind. The
 * account holds no balance of its own - its ledger's is the balance (P10).
 */
final class BankAccountsScreen implements Screen {

    private final ScreenContext context;
    private final Banking banking;
    private final TableView<BankAccount> table = new TableView<>();
    private final TextField name = new TextField();
    private final TextField bank = new TextField();
    private final TextField branch = new TextField();
    private final TextField number = new TextField();
    private final TextField ifsc = new TextField();
    private final ComboBox<String> type = new ComboBox<>();
    private final Button open = new Button("Open account");
    private final Label message = new Label();
    private final BorderPane view = new BorderPane();

    BankAccountsScreen(ScreenContext context) {
        this.context = context;
        this.banking = new Banking(context.transactions(), context.guard(), context.clock());

        table.setId("bankAccounts");
        table.setPlaceholder(new Label("No bank accounts yet."));
        table.getColumns().add(column("Account", BankAccount::ledgerName, 180));
        table.getColumns().add(column("Bank", BankAccount::bankName, 170));
        table.getColumns().add(column("Branch", a -> a.branch() == null ? "" : a.branch(), 110));
        table.getColumns().add(column("Number", BankAccount::accountNumber, 130));
        table.getColumns().add(column("IFSC", a -> a.ifsc() == null ? "" : a.ifsc(), 110));
        table.getColumns().add(column("Type", a -> label(a.type()), 90));

        name.setId("accountName");
        name.setPromptText("As it appears in the books, e.g. HDFC Current");
        bank.setId("bankName");
        branch.setId("branch");
        number.setId("accountNumber");
        ifsc.setId("ifsc");
        type.setId("accountType");
        type.getItems().setAll("CURRENT", "SAVINGS", "CASH_CREDIT", "OVERDRAFT");
        type.setValue("CURRENT");
        type.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String t) {
                return t == null ? "" : label(t);
            }

            @Override
            public String fromString(String s) {
                return null;
            }
        });
        open.setId("openAccount");
        message.setId("bankMessage");
        message.setWrapText(true);
        open.setOnAction(e -> open());
        Keyboard.enterAdvances(List.of(name, bank, branch, number, ifsc, type), open);
        if (!context.state().allowsMutation()) {
            open.setDisable(true);
        }

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        int row = 0;
        form.addRow(row++, new Label("Account name"), name);
        form.addRow(row++, new Label("Bank"), bank);
        form.addRow(row++, new Label("Branch"), branch);
        form.addRow(row++, new Label("Account number"), number);
        form.addRow(row++, new Label("IFSC"), ifsc);
        form.addRow(row++, new Label("Type"), type);
        form.add(open, 1, row);
        VBox side = new VBox(12, form, message);
        side.setPadding(new Insets(0, 0, 0, 16));
        side.setPrefWidth(420);
        view.setId("bankAccountsScreen");
        view.setPadding(new Insets(16));
        view.setCenter(table);
        view.setRight(side);
        load();
    }

    @Override
    public Node view() {
        return view;
    }

    @Override
    public void shown() {
        load();
    }

    private void load() {
        context.background("Reading bank accounts", banking::accounts, table.getItems()::setAll);
    }

    private void open() {
        String n = name.getText();
        String b = bank.getText();
        String br = branch.getText();
        String no = number.getText();
        String code = ifsc.getText();
        String t = type.getValue();
        context.background("Opening the bank account", () -> banking.openAccount(n, b, br, no, code, t), a -> {
            say("Opened " + a.ledgerName() + ".");
            for (TextField f : List.of(name, bank, branch, number, ifsc)) {
                f.clear();
            }
            load();
        });
    }

    private static String label(String type) {
        return switch (type) {
            case "CURRENT" -> "Current";
            case "SAVINGS" -> "Savings";
            case "CASH_CREDIT" -> "Cash credit";
            case "OVERDRAFT" -> "Overdraft";
            default -> type;
        };
    }

    private void say(String text) {
        message.setText(text);
        context.notify(text);
    }

    private static TableColumn<BankAccount, String> column(String title, Function<BankAccount, String> value,
                                                           double width) {
        TableColumn<BankAccount, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }
}
