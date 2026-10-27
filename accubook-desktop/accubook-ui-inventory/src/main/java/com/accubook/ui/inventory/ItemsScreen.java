package com.accubook.ui.inventory;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.inventory.Inventory;
import com.accubook.inventory.Item;
import com.accubook.inventory.ItemKind;
import com.accubook.inventory.ItemTaxRate;
import com.accubook.inventory.Unit;
import com.accubook.tax.GstCalculator;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePackException;
import com.accubook.tax.TaxCategory;
import com.accubook.ui.api.AmountField;
import com.accubook.ui.api.AmountInput;
import com.accubook.ui.api.DateField;
import com.accubook.ui.api.Keyboard;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Items with their unit and GST (P9-2). A new item is created only once its GST
 * entry is known to be valid, so a refused rate never leaves an item without
 * tax. A rate change is a new entry from a date; documents already drafted or
 * posted keep the rate they were taxed at.
 */
final class ItemsScreen implements Screen {

    /** An item with its GST today, for the list. */
    record Row(Item item, String unit, ItemTaxRate tax) {
    }

    private final ScreenContext context;
    private final Inventory inventory;
    private final TableView<Row> table = new TableView<>();
    private final TextField name = new TextField();
    private final ComboBox<ItemKind> kind = new ComboBox<>();
    private final ComboBox<Unit> unit = new ComboBox<>();
    private final TextField hsn = new TextField();
    private final TextField sku = new TextField();
    private final ComboBox<TaxCategory> category = new ComboBox<>();
    private final AmountField rate = new AmountField(AmountInput.ofScale(4));
    private final AmountField cess = new AmountField(AmountInput.ofScale(4));
    private final DateField from;
    private final Button add = new Button("Add item");
    private final Button saveTax = new Button("Record GST from date");
    private final Button toggle = new Button("Deactivate");
    private final TextField unitSymbol = new TextField();
    private final TextField unitName = new TextField();
    private final TextField unitDecimals = new TextField("0");
    private final Button addUnit = new Button("Add unit");
    private final Label message = new Label();
    private final BorderPane view = new BorderPane();
    private RulePack rules;
    private LocalDate booksBegin;

    ItemsScreen(ScreenContext context) {
        this.context = context;
        this.inventory = new Inventory(context.transactions(), context.guard(), context.clock());
        this.from = new DateField(() -> LocalDate.now(context.clock()), context::fiscalYearStart);

        table.setId("itemTable");
        table.setPlaceholder(new Label("No items yet."));
        table.getColumns().add(Columns.of("Item", (Row r) -> r.item().name() + (r.item().active() ? "" : "  (inactive)"),
                220));
        table.getColumns().add(Columns.of("Kind", (Row r) -> r.item().kind() == ItemKind.GOODS ? "Goods" : "Service",
                70));
        table.getColumns().add(Columns.of("Unit", Row::unit, 60));
        table.getColumns().add(Columns.of("HSN/SAC", (Row r) -> nz(r.item().hsnSac()), 90));
        table.getColumns().add(Columns.of("GST today", (Row r) -> r.tax() == null ? "not set" : describe(r.tax()), 140));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, r) -> show(r));

        name.setId("itemName");
        kind.setId("itemKind");
        kind.getItems().setAll(ItemKind.values());
        kind.setValue(ItemKind.GOODS);
        Columns.show(kind, k -> k == ItemKind.GOODS ? "Goods" : "Service");
        unit.setId("itemUnit");
        Columns.show(unit, u -> u.symbol() + " - " + u.name());
        hsn.setId("itemHsn");
        sku.setId("itemSku");
        category.setId("taxCategory");
        category.getItems().setAll(TaxCategory.values());
        category.setValue(TaxCategory.TAXABLE);
        Columns.show(category, ItemsScreen::label);
        rate.setId("gstRate");
        rate.setPromptText("%");
        cess.setId("cessRate");
        cess.setPromptText("%");
        from.setId("taxFrom");
        add.setId("addItem");
        saveTax.setId("saveTax");
        toggle.setId("toggleItem");
        unitSymbol.setId("unitSymbol");
        unitName.setId("unitName");
        unitDecimals.setId("unitDecimals");
        addUnit.setId("addUnit");
        message.setId("itemMessage");
        message.setWrapText(true);
        add.setOnAction(e -> add());
        saveTax.setOnAction(e -> saveTax());
        toggle.setOnAction(e -> toggle());
        addUnit.setOnAction(e -> addUnit());
        Keyboard.enterAdvances(List.of(name, kind, unit, hsn, sku, category, rate, cess, from), add);
        Keyboard.enterAdvances(List.of(unitSymbol, unitName, unitDecimals), addUnit);

        GridPane form = grid();
        int row = 0;
        form.addRow(row++, new Label("Name"), name);
        form.addRow(row++, new Label("Kind"), kind);
        form.addRow(row++, new Label("Unit"), unit);
        form.addRow(row++, new Label("HSN / SAC"), hsn);
        form.addRow(row++, new Label("SKU"), sku);
        form.addRow(row++, new Label("GST category"), category);
        form.addRow(row++, new Label("GST rate %"), rate);
        form.addRow(row++, new Label("Cess %"), cess);
        form.addRow(row++, new Label("GST from"), from);
        GridPane units = grid();
        units.addRow(0, new Label("Symbol"), unitSymbol);
        units.addRow(1, new Label("Name"), unitName);
        units.addRow(2, new Label("Decimal places"), unitDecimals);
        units.add(addUnit, 1, 3);
        VBox side = new VBox(12, form, new HBox(8, add, saveTax, toggle), message, heading("New unit"), units);
        side.setPadding(new Insets(0, 0, 0, 16));
        ScrollPane scroll = new ScrollPane(side);
        scroll.setFitToWidth(true);
        scroll.setPrefWidth(460);
        if (!context.state().allowsMutation()) {
            for (Button b : List.of(add, saveTax, toggle, addUnit)) {
                b.setDisable(true);
            }
        }
        view.setId("itemsScreen");
        view.setPadding(new Insets(16));
        view.setCenter(table);
        view.setRight(scroll);
        show(null);
        load(null);
    }

    @Override
    public Node view() {
        return view;
    }

    @Override
    public void shown() {
        Row r = table.getSelectionModel().getSelectedItem();
        load(r == null ? null : r.item().id());
    }

    private record Loaded(RulePack rules, LocalDate booksBegin, List<Unit> units, List<Row> rows) {
    }

    private void load(String keepId) {
        context.background("Reading items", () -> {
            List<Unit> units = inventory.units();
            Map<String, String> symbols = new HashMap<>();
            units.forEach(u -> symbols.put(u.id(), u.symbol()));
            LocalDate today = LocalDate.now(context.clock());
            List<Row> rows = inventory.items().stream().map(i -> new Row(i, symbols.getOrDefault(i.unitId(), ""),
                    inventory.taxOn(i.id(), today).orElse(null))).toList();
            LocalDate begin = context.transactions().read(TrialBalanceQuery::fiscalYears).stream()
                    .map(FiscalYear::startDate).min(LocalDate::compareTo).orElse(context.fiscalYearStart());
            return new Loaded(context.rules().get(), begin, units, rows);
        }, loaded -> {
            rules = loaded.rules();
            booksBegin = loaded.booksBegin();
            Unit chosen = unit.getValue();
            unit.getItems().setAll(loaded.units());
            unit.setValue(chosen);
            if (from.getValue() == null) {
                from.setValue(booksBegin);
            }
            table.getItems().setAll(loaded.rows());
            if (keepId != null) {
                table.getItems().stream().filter(r -> r.item().id().equals(keepId)).findFirst()
                        .ifPresent(r -> table.getSelectionModel().select(r));
            }
        });
    }

    private void show(Row r) {
        boolean editable = context.state().allowsMutation();
        saveTax.setDisable(!editable || r == null);
        toggle.setDisable(!editable || r == null);
        toggle.setText(r != null && !r.item().active() ? "Reactivate" : "Deactivate");
        if (r == null) {
            return;
        }
        name.setText(r.item().name());
        kind.setValue(r.item().kind());
        unit.setValue(unit.getItems().stream().filter(u -> u.id().equals(r.item().unitId())).findFirst()
                .orElse(null));
        hsn.setText(nz(r.item().hsnSac()));
        sku.setText(nz(r.item().sku()));
        if (r.tax() != null) {
            category.setValue(r.tax().category());
            rate.setValue(r.tax().gstRate());
            cess.setValue(r.tax().cessRate());
        }
    }

    /** The GST entry the form describes, checked against the rule pack, or a message. */
    private Optional<String> taxProblem() {
        if (!rate.commit() || !cess.commit()) {
            return Optional.of("The GST and cess rates are percentages, up to four places");
        }
        if (!from.commit() || from.getValue() == null) {
            return Optional.of("Enter the date the GST applies from");
        }
        if (rules == null) {
            return Optional.of("The rule pack is still loading; try again");
        }
        try {
            GstCalculator.requireValid(category.getValue(), zero(rate.getValue()), zero(cess.getValue()),
                    from.getValue(), rules);
            return Optional.empty();
        } catch (RulePackException e) {
            return Optional.of(e.getMessage());
        }
    }

    private void add() {
        if (unit.getValue() == null) {
            say("Choose the item's unit, or add one below.");
            return;
        }
        Optional<String> problem = taxProblem();
        if (problem.isPresent()) {
            say(problem.get());
            return;
        }
        String itemName = name.getText();
        ItemKind itemKind = kind.getValue();
        String unitId = unit.getValue().id();
        String hsnSac = hsn.getText();
        String code = sku.getText();
        TaxCategory cat = category.getValue();
        BigDecimal gst = zero(rate.getValue());
        BigDecimal cessRate = zero(cess.getValue());
        LocalDate date = from.getValue();
        context.background("Adding the item", () -> {
            Item item = inventory.createItem(itemName, itemKind, unitId, code, null, hsnSac, null);
            inventory.setTax(item.id(), date, cat, gst, cessRate, context.rules().get());
            return item;
        }, item -> {
            say("Added " + item.name() + ".");
            name.clear();
            hsn.clear();
            sku.clear();
            load(null);
        });
    }

    private void saveTax() {
        Row r = table.getSelectionModel().getSelectedItem();
        if (r == null) {
            return;
        }
        Optional<String> problem = taxProblem();
        if (problem.isPresent()) {
            say(problem.get());
            return;
        }
        TaxCategory cat = category.getValue();
        BigDecimal gst = zero(rate.getValue());
        BigDecimal cessRate = zero(cess.getValue());
        LocalDate date = from.getValue();
        context.background("Recording the GST", () -> inventory.setTax(r.item().id(), date, cat, gst, cessRate,
                context.rules().get()), t -> {
            say(r.item().name() + ": " + describe(t) + " from " + com.accubook.ui.api.DateInput.format(date) + ".");
            load(r.item().id());
        });
    }

    private void toggle() {
        Row r = table.getSelectionModel().getSelectedItem();
        if (r == null) {
            return;
        }
        boolean active = r.item().active();
        context.background(active ? "Deactivating the item" : "Reactivating the item", () -> {
            if (active) {
                inventory.deactivateItem(r.item().id());
            } else {
                inventory.reactivateItem(r.item().id());
            }
            return r.item().id();
        }, id -> {
            say(r.item().name() + (active ? " deactivated." : " reactivated."));
            load(id);
        });
    }

    private void addUnit() {
        int decimals;
        try {
            decimals = Integer.parseInt(unitDecimals.getText().strip());
        } catch (NumberFormatException e) {
            say("Decimal places is a whole number from 0 to 4.");
            return;
        }
        String symbol = unitSymbol.getText();
        String unitLabel = unitName.getText();
        context.background("Adding the unit", () -> inventory.createUnit(symbol, unitLabel, decimals), u -> {
            say("Added unit " + u.symbol() + ".");
            unitSymbol.clear();
            unitName.clear();
            load(null);
            unit.setValue(u);
        });
    }

    static String describe(ItemTaxRate t) {
        String text = label(t.category());
        if (t.category() == TaxCategory.TAXABLE) {
            text = t.gstRate().stripTrailingZeros().toPlainString() + "%";
        }
        if (t.cessRate().signum() > 0) {
            text += " + cess " + t.cessRate().stripTrailingZeros().toPlainString() + "%";
        }
        return text;
    }

    static String label(TaxCategory c) {
        return switch (c) {
            case TAXABLE -> "Taxable";
            case NIL_RATED -> "Nil-rated";
            case EXEMPT -> "Exempt";
            case NON_GST -> "Non-GST";
        };
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        return grid;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private void say(String text) {
        message.setText(text);
        context.notify(text);
    }
}
