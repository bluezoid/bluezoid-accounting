package com.accubook.ui.shell;

import com.accubook.accounting.chart.ChartOfAccounts;
import com.accubook.accounting.chart.LedgerAccount;
import com.accubook.accounting.chart.LedgerGroup;
import com.accubook.ui.api.Keyboard;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The chart of accounts: groups as a tree with their accounts, a new sub-group
 * or account under the selected group (S2-1, S2-2), and an account renamed,
 * deactivated or reactivated. The engine's refusals - a root group, an account
 * under Sundry Debtors, a system account deactivated - are shown as they are.
 */
final class ChartScreen implements Screen {

    /** A row of the tree: a group or an account. */
    record Entry(LedgerGroup group, LedgerAccount account) {

        @Override
        public String toString() {
            if (group != null) {
                return group.name();
            }
            return account.name() + (account.active() ? "" : "  (inactive)");
        }
    }

    private final ScreenContext context;
    private final ChartOfAccounts chart;
    private final TreeView<Entry> tree = new TreeView<>();
    private final TextField groupName = new TextField();
    private final TextField ledgerName = new TextField();
    private final TextField newName = new TextField();
    private final Button addGroup = new Button("Add group");
    private final Button addLedger = new Button("Add account");
    private final Button rename = new Button("Rename");
    private final Button toggle = new Button("Deactivate");
    private final Label selected = new Label("Select a group or an account");
    private final Label message = new Label();
    private final BorderPane view = new BorderPane();

    ChartScreen(ScreenContext context) {
        this.context = context;
        this.chart = new ChartOfAccounts(context.transactions());

        tree.setId("chartTree");
        tree.setShowRoot(false);
        tree.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> select(item));

        groupName.setId("newGroupName");
        groupName.setPromptText("New group under the selected one");
        ledgerName.setId("newLedgerName");
        ledgerName.setPromptText("New account in the selected group");
        newName.setId("renameTo");
        addGroup.setId("addGroup");
        addLedger.setId("addLedger");
        rename.setId("renameLedger");
        toggle.setId("toggleLedger");
        selected.setId("selectedEntry");
        message.setId("chartMessage");
        message.setWrapText(true);
        addGroup.setOnAction(e -> addGroup());
        addLedger.setOnAction(e -> addLedger());
        rename.setOnAction(e -> rename());
        toggle.setOnAction(e -> toggle());
        Keyboard.enterAdvances(List.of(groupName), addGroup);
        Keyboard.enterAdvances(List.of(ledgerName), addLedger);
        Keyboard.enterAdvances(List.of(newName), rename);

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.addRow(0, new Label("Selected"), selected);
        form.addRow(1, new Label("New group"), groupName, addGroup);
        form.addRow(2, new Label("New account"), ledgerName, addLedger);
        form.addRow(3, new Label("Rename account"), newName, rename);
        form.add(toggle, 2, 4);
        VBox side = new VBox(12, form, message);
        side.setPadding(new Insets(0, 0, 0, 16));
        side.setPrefWidth(460);

        if (!context.state().allowsMutation()) {
            for (Button b : List.of(addGroup, addLedger, rename, toggle)) {
                b.setDisable(true);
            }
        }
        view.setPadding(new Insets(16));
        view.setCenter(tree);
        view.setRight(side);
        view.setId("chartScreen");
        updateButtons(null);
        load(null);
    }

    @Override
    public Node view() {
        return view;
    }

    @Override
    public void shown() {
        load(currentId());
    }

    private record Loaded(List<LedgerGroup> groups, List<LedgerAccount> accounts) {
    }

    /** Read the chart and rebuild the tree, keeping {@code keepId} selected. */
    private void load(String keepId) {
        context.background("Reading the chart of accounts", () -> new Loaded(chart.groups(), chart.allAccounts()),
                loaded -> {
                    TreeItem<Entry> root = new TreeItem<>();
                    Map<String, TreeItem<Entry>> byGroup = new HashMap<>();
                    for (LedgerGroup g : loaded.groups()) {
                        TreeItem<Entry> item = new TreeItem<>(new Entry(g, null));
                        item.setExpanded(true);
                        byGroup.put(g.id(), item);
                    }
                    for (LedgerGroup g : loaded.groups()) {
                        TreeItem<Entry> parent = g.parentId() == null ? root : byGroup.get(g.parentId());
                        (parent == null ? root : parent).getChildren().add(byGroup.get(g.id()));
                    }
                    for (LedgerAccount a : loaded.accounts()) {
                        TreeItem<Entry> group = byGroup.get(a.groupId());
                        if (group != null) {
                            group.getChildren().add(new TreeItem<>(new Entry(null, a)));
                        }
                    }
                    tree.setRoot(root);
                    if (keepId != null) {
                        select(root, keepId);
                    }
                });
    }

    private void select(TreeItem<Entry> item, String id) {
        for (TreeItem<Entry> child : item.getChildren()) {
            Entry e = child.getValue();
            if (id.equals(e.group() != null ? e.group().id() : e.account().id())) {
                tree.getSelectionModel().select(child);
                tree.scrollTo(tree.getRow(child));
                return;
            }
            select(child, id);
        }
    }

    private String currentId() {
        Entry e = entry();
        return e == null ? null : e.group() != null ? e.group().id() : e.account().id();
    }

    private Entry entry() {
        TreeItem<Entry> item = tree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private void select(TreeItem<Entry> item) {
        Entry e = item == null ? null : item.getValue();
        selected.setText(e == null ? "Select a group or an account"
                : (e.group() != null ? "Group: " : "Account: ") + e);
        if (e != null && e.account() != null) {
            newName.setText(e.account().name());
        }
        updateButtons(e);
    }

    private void updateButtons(Entry e) {
        if (!context.state().allowsMutation()) {
            return;
        }
        boolean group = e != null && e.group() != null;
        boolean account = e != null && e.account() != null;
        addGroup.setDisable(!group);
        addLedger.setDisable(!group);
        rename.setDisable(!account);
        toggle.setDisable(!account);
        toggle.setText(account && !e.account().active() ? "Reactivate" : "Deactivate");
    }

    private void addGroup() {
        Entry e = entry();
        if (e == null || e.group() == null) {
            say("Select the group the new one goes under.");
            return;
        }
        String name = groupName.getText();
        context.background("Adding the group", () -> chart.createGroup(name, e.group().id(), now()), g -> {
            groupName.clear();
            say("Added group " + g.name() + " under " + e.group().name() + ".");
            load(g.id());
        });
    }

    private void addLedger() {
        Entry e = entry();
        if (e == null || e.group() == null) {
            say("Select the group the account goes in.");
            return;
        }
        String name = ledgerName.getText();
        context.background("Adding the account", () -> chart.createLedger(name, e.group().id(), now()), a -> {
            ledgerName.clear();
            say("Added account " + a.name() + " in " + e.group().name() + ".");
            load(a.id());
        });
    }

    private void rename() {
        Entry e = entry();
        if (e == null || e.account() == null) {
            return;
        }
        String id = e.account().id();
        String name = newName.getText();
        context.background("Renaming the account", () -> {
            chart.rename(id, name, now());
            return name.strip();
        }, n -> {
            say("Renamed to " + n + ".");
            load(id);
        });
    }

    private void toggle() {
        Entry e = entry();
        if (e == null || e.account() == null) {
            return;
        }
        LedgerAccount account = e.account();
        context.background(account.active() ? "Deactivating the account" : "Reactivating the account", () -> {
            if (account.active()) {
                chart.deactivate(account.id(), now());
            } else {
                chart.reactivate(account.id(), now());
            }
            return !account.active();
        }, active -> {
            say(account.name() + (active ? " reactivated." : " deactivated; its history stays in the books."));
            load(account.id());
        });
    }

    private long now() {
        return context.clock().millis();
    }

    private void say(String text) {
        message.setText(Objects.requireNonNullElse(text, ""));
        context.notify(text);
    }
}
