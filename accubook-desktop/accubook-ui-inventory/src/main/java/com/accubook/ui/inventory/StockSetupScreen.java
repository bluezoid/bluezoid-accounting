package com.accubook.ui.inventory;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.inventory.Inventory;
import com.accubook.inventory.Item;
import com.accubook.inventory.ItemKind;
import com.accubook.inventory.MovementRequest;
import com.accubook.inventory.MovementType;
import com.accubook.inventory.Warehouse;
import com.accubook.ui.api.AmountField;
import com.accubook.ui.api.AmountFormat;
import com.accubook.ui.api.AmountInput;
import com.accubook.ui.api.DateInput;
import com.accubook.ui.api.Keyboard;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Warehouses, and the stock on hand when the books begin, item by item and
 * dated the first day. That stock is what the opening Stock-in-Hand is
 * computed from (S2-5); entered after the opening balances are posted, they
 * must be replaced - the screen says so, and year 1 will not close until done.
 */
final class StockSetupScreen implements Screen {

    /** One opening stock entry, for the list. */
    record Opening(String item, String warehouse, BigDecimal quantity, BigDecimal unitCost) {

        BigDecimal value() {
            return quantity.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
        }
    }

    private final ScreenContext context;
    private final Inventory inventory;
    private final ListView<Warehouse> warehouses = new ListView<>();
    private final TextField warehouseName = new TextField();
    private final TextField warehouseAddress = new TextField();
    private final Button addWarehouse = new Button("Add warehouse");
    private final TableView<Opening> openings = new TableView<>();
    private final ComboBox<Item> item = new ComboBox<>();
    private final ComboBox<Warehouse> into = new ComboBox<>();
    private final AmountField quantity = new AmountField(AmountInput.ofScale(4));
    private final AmountField cost = new AmountField(AmountInput.ofScale(4));
    private final Button addOpening = new Button("Add opening stock");
    private final Label total = new Label();
    private final Label dated = new Label();
    private final Label message = new Label();
    private final ScrollPane view;
    private LocalDate booksBegin;
    private boolean openingPosted;

    StockSetupScreen(ScreenContext context) {
        this.context = context;
        this.inventory = new Inventory(context.transactions(), context.guard(), context.clock());

        warehouses.setId("warehouseList");
        warehouses.setPrefHeight(120);
        warehouses.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Warehouse w, boolean empty) {
                super.updateItem(w, empty);
                setText(empty || w == null ? null : w.name() + (w.active() ? "" : "  (inactive)"));
            }
        });
        warehouseName.setId("warehouseName");
        warehouseAddress.setId("warehouseAddress");
        addWarehouse.setId("addWarehouse");
        addWarehouse.setOnAction(e -> addWarehouse());
        Keyboard.enterAdvances(List.of(warehouseName, warehouseAddress), addWarehouse);

        openings.setId("openingStock");
        openings.setPrefHeight(220);
        openings.setPlaceholder(new Label("No opening stock entered."));
        openings.getColumns().add(Columns.of("Item", Opening::item, 200));
        openings.getColumns().add(Columns.of("Warehouse", Opening::warehouse, 130));
        openings.getColumns().add(Columns.of("Quantity", (Opening o) -> o.quantity().stripTrailingZeros()
                .toPlainString(), 90));
        openings.getColumns().add(Columns.of("Unit cost", (Opening o) -> AmountFormat.format(o.unitCost()), 100));
        openings.getColumns().add(Columns.of("Value", (Opening o) -> AmountFormat.format(o.value()), 120));
        item.setId("openingItem");
        Columns.show(item, Item::name);
        into.setId("openingWarehouse");
        Columns.show(into, Warehouse::name);
        quantity.setId("openingQuantity");
        cost.setId("openingCost");
        addOpening.setId("addOpening");
        addOpening.setOnAction(e -> addOpening());
        total.setId("openingTotal");
        dated.setId("openingDate");
        message.setId("stockMessage");
        message.setWrapText(true);
        Keyboard.enterAdvances(List.of(item, into, quantity, cost), addOpening);

        GridPane warehouseForm = grid();
        warehouseForm.addRow(0, new Label("Name"), warehouseName);
        warehouseForm.addRow(1, new Label("Address"), warehouseAddress);
        warehouseForm.add(addWarehouse, 1, 2);
        GridPane openingForm = grid();
        openingForm.addRow(0, new Label("Item"), item);
        openingForm.addRow(1, new Label("Warehouse"), into);
        openingForm.addRow(2, new Label("Quantity"), quantity);
        openingForm.addRow(3, new Label("Unit cost"), cost);
        openingForm.add(addOpening, 1, 4);
        if (!context.state().allowsMutation()) {
            addWarehouse.setDisable(true);
            addOpening.setDisable(true);
        }
        VBox content = new VBox(10, heading("Warehouses"), warehouses, warehouseForm, heading("Opening stock"), dated,
                openings, total, openingForm, message);
        content.setPadding(new Insets(16));
        view = new ScrollPane(content);
        view.setFitToWidth(true);
        view.setId("stockSetupScreen");
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

    private record Loaded(LocalDate booksBegin, boolean openingPosted, List<Warehouse> warehouses, List<Item> items,
                          List<Opening> openings) {
    }

    private void load() {
        context.background("Reading stock", () -> {
            LocalDate begin = context.transactions().read(TrialBalanceQuery::fiscalYears).stream()
                    .map(FiscalYear::startDate).min(LocalDate::compareTo).orElse(context.fiscalYearStart());
            boolean posted = context.transactions().read(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM fiscal_year_event WHERE action = 'OPENING'");
                     ResultSet r = ps.executeQuery()) {
                    r.next();
                    return r.getInt(1) > 0;
                }
            });
            List<Warehouse> all = inventory.warehouses();
            Map<String, String> names = new HashMap<>();
            all.forEach(w -> names.put(w.id(), w.name()));
            List<Item> goods = inventory.items().stream().filter(i -> i.kind() == ItemKind.GOODS).toList();
            List<Opening> list = new ArrayList<>();
            for (Item i : goods) {
                for (MovementRequest m : inventory.movementsOf(i.id())) {
                    if (m.type() == MovementType.OPENING) {
                        list.add(new Opening(i.name(), names.getOrDefault(m.toWarehouseId(), ""), m.quantity(),
                                m.unitCost()));
                    }
                }
            }
            return new Loaded(begin, posted, all, goods, list);
        }, loaded -> {
            booksBegin = loaded.booksBegin();
            openingPosted = loaded.openingPosted();
            dated.setText("Dated " + DateInput.format(booksBegin) + ", the day the books begin.");
            warehouses.getItems().setAll(loaded.warehouses());
            List<Warehouse> active = loaded.warehouses().stream().filter(Warehouse::active).toList();
            Warehouse w = into.getValue();
            into.getItems().setAll(active);
            into.setValue(w != null ? w : active.isEmpty() ? null : active.get(0));
            Item chosen = item.getValue();
            item.getItems().setAll(loaded.items().stream().filter(Item::active).toList());
            item.setValue(chosen);
            openings.getItems().setAll(loaded.openings());
            BigDecimal sum = loaded.openings().stream().map(Opening::value).reduce(BigDecimal.ZERO, BigDecimal::add);
            total.setText("Opening stock value: " + AmountFormat.format(sum));
        });
    }

    private void addWarehouse() {
        String n = warehouseName.getText();
        String a = warehouseAddress.getText();
        context.background("Adding the warehouse", () -> inventory.createWarehouse(n, a), w -> {
            say("Added warehouse " + w.name() + ".");
            warehouseName.clear();
            warehouseAddress.clear();
            into.setValue(w);
            load();
        });
    }

    private void addOpening() {
        if (item.getValue() == null || into.getValue() == null) {
            say("Choose the item and the warehouse it is in.");
            return;
        }
        if (!quantity.commit() || quantity.getValue() == null || quantity.getValue().signum() <= 0) {
            say("Enter the quantity on hand.");
            return;
        }
        if (!cost.commit() || cost.getValue() == null) {
            say("Enter the unit cost; opening stock is valued at what it cost.");
            return;
        }
        MovementRequest request = MovementRequest.in(MovementType.OPENING, item.getValue().id(), into.getValue().id(),
                quantity.getValue(), cost.getValue(), booksBegin, "Opening stock");
        String itemName = item.getValue().name();
        context.background("Adding opening stock", () -> inventory.record(request), id -> {
            say("Added opening stock of " + itemName + "."
                    + (openingPosted ? " The opening balances are already posted: replace them so Stock-in-Hand "
                    + "carries this stock." : ""));
            quantity.setValue(null);
            cost.setValue(null);
            load();
        });
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        return grid;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        return label;
    }

    private void say(String text) {
        message.setText(text);
        context.notify(text);
    }
}
