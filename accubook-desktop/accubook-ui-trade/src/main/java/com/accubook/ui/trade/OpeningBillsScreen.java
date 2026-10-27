package com.accubook.ui.trade;

import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.ModuleId;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.OpeningBill;
import com.accubook.trade.OpeningBillRequest;
import com.accubook.trade.OpeningBills;
import com.accubook.ui.api.AmountField;
import com.accubook.ui.api.AmountFormat;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A side's opening bills (S2-4): each unpaid invoice or bill from before the
 * books, with its own date and due date, so it is paid and aged like any
 * other. Its voucher moves the amount between the party and Opening Balance
 * Equity on the first day. Cancelled, with a reason, only while nothing is
 * applied to it and the first year is open.
 */
final class OpeningBillsScreen implements Screen {

    /** A bill with its party's name, for the list. */
    record Row(OpeningBill bill, String party) {
    }

    private final ScreenContext context;
    private final DocumentKind.Side side;
    private final OpeningBills bills;
    private final Parties parties;
    private final TableView<Row> table = new TableView<>();
    private final ComboBox<Party> party = new ComboBox<>();
    private final TextField number = new TextField();
    private final DateField billDate;
    private final DateField dueDate;
    private final AmountField amount = AmountField.money();
    private final TextField narration = new TextField();
    private final Button add = new Button("Add");
    private final TextField reason = new TextField();
    private final Button cancel = new Button("Cancel selected");
    private final Label total = new Label();
    private final Label message = new Label();
    private final BorderPane view = new BorderPane();

    OpeningBillsScreen(ScreenContext context, DocumentKind.Side side, ModuleId module) {
        this.context = context;
        this.side = side;
        this.bills = new OpeningBills(side, module, context.transactions(), context.guard(),
                new TransactionalPosting(context.guard(), context.clock()), context.clock());
        this.parties = new Parties(context.transactions());
        // Opening bills are from before the books, so a bare day and month fall in the year before.
        this.billDate = new DateField(() -> LocalDate.now(context.clock()), () -> context.fiscalYearStart()
                .minusYears(1));
        // A due date typed as day and month falls on or after the bill's month.
        this.dueDate = new DateField(() -> LocalDate.now(context.clock()), () -> billDate.getValue() == null
                ? context.fiscalYearStart() : billDate.getValue().withDayOfMonth(1));

        String noun = sales() ? "invoice" : "bill";
        table.setId("openingBills");
        table.setPlaceholder(new Label("No opening " + noun + "s entered."));
        table.getColumns().add(column("Party", Row::party, 180));
        table.getColumns().add(column("Number", r -> r.bill().number(), 100));
        table.getColumns().add(column("Date", r -> DateInput.format(r.bill().billDate()), 90));
        table.getColumns().add(column("Due", r -> DateInput.format(r.bill().dueDate()), 90));
        table.getColumns().add(column("Amount", r -> AmountFormat.format(r.bill().amount()), 110));
        table.getColumns().add(column("Outstanding", r -> AmountFormat.format(r.bill().outstanding()), 110));
        table.getColumns().add(column("Status", r -> r.bill().cancelled() ? "Cancelled" : "", 80));

        party.setId("billParty");
        party.setConverter(new StringConverter<>() {
            @Override
            public String toString(Party p) {
                return p == null ? "" : p.name();
            }

            @Override
            public Party fromString(String s) {
                return null;
            }
        });
        party.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Party p, boolean empty) {
                super.updateItem(p, empty);
                setText(empty || p == null ? "" : p.name());
            }
        });
        number.setId("billNumber");
        billDate.setId("billDate");
        dueDate.setId("billDue");
        amount.setId("billAmount");
        narration.setId("billNarration");
        add.setId("addBill");
        reason.setId("cancelReason");
        reason.setPromptText("Why it is cancelled");
        cancel.setId("cancelBill");
        total.setId("billTotal");
        message.setId("billMessage");
        message.setWrapText(true);
        add.setOnAction(e -> add());
        cancel.setOnAction(e -> cancel());
        Keyboard.enterAdvances(List.of(party, number, billDate, dueDate, amount, narration), add);
        if (!context.state().allowsMutation()) {
            add.setDisable(true);
            cancel.setDisable(true);
        }

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        int row = 0;
        form.addRow(row++, new Label(sales() ? "Customer" : "Vendor"), party);
        form.addRow(row++, new Label(sales() ? "Invoice number" : "Vendor's bill number"), number);
        form.addRow(row++, new Label("Dated"), billDate);
        form.addRow(row++, new Label("Due on"), dueDate);
        form.addRow(row++, new Label("Still owed"), amount);
        form.addRow(row++, new Label("Narration"), narration);
        form.add(add, 1, row);
        VBox panel = new VBox(12, form, message, new HBox(8, reason, cancel));
        panel.setPadding(new Insets(0, 0, 0, 16));
        panel.setPrefWidth(430);
        VBox centre = new VBox(8, table, total);
        view.setId("openingBillsScreen");
        view.setPadding(new Insets(16));
        view.setCenter(centre);
        view.setRight(panel);
        load();
    }

    private boolean sales() {
        return side == DocumentKind.Side.SALES;
    }

    @Override
    public Node view() {
        return view;
    }

    @Override
    public void shown() {
        load();
    }

    private record Loaded(List<Party> parties, List<Row> rows) {
    }

    private void load() {
        context.background("Reading opening " + (sales() ? "invoices" : "bills"), () -> {
            List<Party> all = parties.all();
            Map<String, String> names = new HashMap<>();
            all.forEach(p -> names.put(p.id(), p.name()));
            List<Party> eligible = all.stream().filter(Party::active)
                    .filter(p -> p.role() == PartyRole.BOTH || p.role() == (sales() ? PartyRole.CUSTOMER
                            : PartyRole.VENDOR)).toList();
            List<Row> rows = bills.list().stream().map(b -> new Row(b, names.getOrDefault(b.partyId(), ""))).toList();
            return new Loaded(eligible, rows);
        }, loaded -> {
            Party chosen = party.getValue();
            party.getItems().setAll(loaded.parties());
            party.setValue(chosen);
            table.getItems().setAll(loaded.rows());
            BigDecimal owed = loaded.rows().stream().filter(r -> !r.bill().cancelled())
                    .map(r -> r.bill().outstanding()).reduce(BigDecimal.ZERO, BigDecimal::add);
            total.setText("Outstanding on opening " + (sales() ? "invoices: " : "bills: ") + AmountFormat.format(owed));
        });
    }

    private void add() {
        if (party.getValue() == null) {
            say("Choose the " + (sales() ? "customer." : "vendor."));
            return;
        }
        if (!billDate.commit() || billDate.getValue() == null) {
            say("Enter the date of the " + (sales() ? "invoice." : "bill."));
            return;
        }
        if (!dueDate.commit() || !amount.commit() || amount.getValue() == null) {
            say(amount.getValue() == null ? "Enter what is still owed on it." : "Enter the due date as dd-mm-yyyy.");
            return;
        }
        OpeningBillRequest request;
        try {
            request = new OpeningBillRequest(party.getValue().id(), number.getText(), billDate.getValue(),
                    dueDate.getValue(), amount.getValue(), narration.getText());
        } catch (RuntimeException e) {
            say(e.getMessage());
            return;
        }
        context.background("Adding the opening " + (sales() ? "invoice" : "bill"), () -> bills.create(request), b -> {
            say("Added " + b.number() + " for " + AmountFormat.format(b.amount()) + ".");
            number.clear();
            amount.setValue(null);
            narration.clear();
            dueDate.setValue(null);
            load();
            number.requestFocus();
        });
    }

    private void cancel() {
        Row r = table.getSelectionModel().getSelectedItem();
        if (r == null) {
            say("Select the one to cancel.");
            return;
        }
        String why = reason.getText();
        context.background("Cancelling " + r.bill().number(), () -> bills.cancel(r.bill().id(), why), b -> {
            say(b.number() + " cancelled.");
            reason.clear();
            load();
        });
    }

    private void say(String text) {
        message.setText(text);
        context.notify(text);
    }

    private static TableColumn<Row, String> column(String title, Function<Row, String> value, double width) {
        TableColumn<Row, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }
}
