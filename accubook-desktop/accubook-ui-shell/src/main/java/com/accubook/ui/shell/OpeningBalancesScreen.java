package com.accubook.ui.shell;

import com.accubook.accounting.chart.AccountNature;
import com.accubook.accounting.chart.ChartOfAccounts;
import com.accubook.accounting.chart.LedgerAccount;
import com.accubook.accounting.chart.LedgerGroup;
import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.year.FiscalYears;
import com.accubook.accounting.year.OpeningBalance;
import com.accubook.accounting.year.OpeningPosition;
import com.accubook.ui.api.AmountField;
import com.accubook.ui.api.AmountFormat;
import com.accubook.ui.api.DateInput;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The opening balances (P11-1): one debit or credit per balance-sheet account
 * on the day the books begin. Income and expense accounts are nil on a year's
 * first day, so they are not offered. A party's opening is its unpaid bills
 * (S2-4) and Stock-in-Hand is the opening stock's value (S2-5), so neither is
 * typed here. The difference goes to Opening Balance Equity and is shown.
 * Once posted, the balances are replaced - with a reason - while the first
 * year is open (S2-3).
 */
final class OpeningBalancesScreen implements Screen {

    /** One account's line on the form. */
    private record Line(LedgerAccount account, AmountField debit, AmountField credit) {
    }

    private final ScreenContext context;
    private final FiscalYears years;
    private final ChartOfAccounts chart;
    private final Parties parties;
    private final GridPane grid = new GridPane();
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private final Label heading = new Label();
    private final Label stock = new Label();
    private final Label totals = new Label();
    private final Label difference = new Label();
    private final TextField reason = new TextField();
    private final Button post = new Button("Post opening balances");
    private final Label message = new Label();
    private final BorderPane view = new BorderPane();
    private OpeningPosition position;

    OpeningBalancesScreen(ScreenContext context) {
        this.context = context;
        this.years = new FiscalYears(context.transactions(), context.guard(), context.clock());
        this.chart = new ChartOfAccounts(context.transactions());
        this.parties = new Parties(context.transactions());

        heading.setId("openingHeading");
        heading.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        stock.setId("openingStockLine");
        totals.setId("openingTotals");
        difference.setId("openingDifference");
        reason.setId("replaceReason");
        reason.setPromptText("Why the opening balances are being replaced");
        post.setId("postOpening");
        post.setOnAction(e -> post());
        message.setId("openingMessage");
        message.setWrapText(true);
        grid.setId("openingGrid");
        grid.setHgap(12);
        grid.setVgap(4);

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        VBox top = new VBox(6, heading, new Label("Customers' and vendors' openings are their unpaid bills, entered "
                + "as opening bills. Stock-in-Hand is the opening stock's value."));
        top.setPadding(new Insets(0, 0, 12, 0));
        HBox actions = new HBox(8, reason, post);
        actions.setAlignment(Pos.CENTER_LEFT);
        reason.setPrefColumnCount(30);
        VBox bottom = new VBox(6, stock, totals, difference, actions, message);
        bottom.setPadding(new Insets(12, 0, 0, 0));
        view.setId("openingBalancesScreen");
        view.setPadding(new Insets(16));
        view.setTop(top);
        view.setCenter(scroll);
        view.setBottom(bottom);
        load();
    }

    @Override
    public Node view() {
        return view;
    }

    private record Loaded(OpeningPosition position, List<LedgerAccount> accounts, Map<String, String> groupNames) {
    }

    private void load() {
        context.background("Reading the opening balances", () -> {
            OpeningPosition p = years.openingPosition();
            List<LedgerGroup> groups = chart.groups();
            Map<String, LedgerGroup> byId = groups.stream().collect(Collectors.toMap(LedgerGroup::id, g -> g));
            Set<String> partyLedgers = parties.all().stream().map(Party::ledgerId).collect(Collectors.toSet());
            Map<String, String> names = new HashMap<>();
            List<LedgerAccount> accounts = new ArrayList<>();
            for (LedgerAccount a : chart.allAccounts()) {
                boolean balanceSheet = a.nature() == AccountNature.ASSETS || a.nature() == AccountNature.LIABILITIES
                        || a.nature() == AccountNature.EQUITY;
                boolean computed = "OPENING_BALANCE_EQUITY".equals(a.systemCode())
                        || under(byId, a.groupId(), "STOCK_IN_HAND");
                boolean entered = p.lines().stream().anyMatch(l -> l.ledgerId().equals(a.id()));
                if (balanceSheet && !computed && !partyLedgers.contains(a.id()) && (a.active() || entered)) {
                    accounts.add(a);
                    names.put(a.id(), byId.get(a.groupId()).name());
                }
            }
            return new Loaded(p, accounts, names);
        }, this::show);
    }

    private static boolean under(Map<String, LedgerGroup> groups, String groupId, String code) {
        LedgerGroup g = groups.get(groupId);
        for (int depth = 0; g != null && depth < 64; depth++) {
            if (code.equals(g.systemCode())) {
                return true;
            }
            g = g.parentId() == null ? null : groups.get(g.parentId());
        }
        return false;
    }

    private void show(Loaded loaded) {
        position = loaded.position();
        heading.setText("Opening balances on " + DateInput.format(position.booksBegin())
                + (position.posted() ? " - posted" : " - not yet posted"));
        grid.getChildren().clear();
        lines.clear();
        grid.addRow(0, bold("Account"), bold("Group"), bold("Debit"), bold("Credit"));
        Map<String, OpeningBalance> entered = new HashMap<>();
        position.lines().forEach(l -> entered.put(l.ledgerId(), l));
        int row = 1;
        boolean editable = position.editable() && context.state().allowsMutation();
        List<javafx.scene.Node> order = new ArrayList<>();
        for (LedgerAccount a : loaded.accounts()) {
            AmountField debit = AmountField.money();
            AmountField credit = AmountField.money();
            // Found by name in tests and accessibility tools; an id cannot hold spaces.
            String key = a.name().replaceAll("[^A-Za-z0-9]", "");
            debit.setId("dr-" + key);
            credit.setId("cr-" + key);
            OpeningBalance b = entered.get(a.id());
            if (b != null) {
                debit.setValue(b.debit().signum() == 0 ? null : b.debit());
                credit.setValue(b.credit().signum() == 0 ? null : b.credit());
            }
            debit.setDisable(!editable);
            credit.setDisable(!editable);
            debit.valueProperty().addListener((obs, old, v) -> refreshTotals());
            credit.valueProperty().addListener((obs, old, v) -> refreshTotals());
            grid.addRow(row++, new Label(a.name()), new Label(loaded.groupNames().get(a.id())), debit, credit);
            lines.put(a.id(), new Line(a, debit, credit));
            order.add(debit);
            order.add(credit);
        }
        com.accubook.ui.api.Keyboard.enterAdvances(order, post);
        stock.setText(position.openingStock() == null ? "No opening stock entered."
                : "Stock-in-Hand, from the opening stock: " + AmountFormat.format(position.openingStock()));
        reason.setVisible(position.posted());
        reason.setManaged(position.posted());
        post.setText(position.posted() ? "Replace opening balances" : "Post opening balances");
        post.setDisable(!editable);
        if (!position.editable()) {
            message.setText("The first year is closed: correct the opening position with a journal.");
        }
        refreshTotals();
    }

    private void refreshTotals() {
        BigDecimal dr = position == null || position.openingStock() == null ? BigDecimal.ZERO : position.openingStock();
        BigDecimal cr = BigDecimal.ZERO;
        for (Line l : lines.values()) {
            dr = dr.add(nz(l.debit().getValue()));
            cr = cr.add(nz(l.credit().getValue()));
        }
        totals.setText("Debits " + AmountFormat.format(dr) + "   Credits " + AmountFormat.format(cr));
        BigDecimal diff = dr.subtract(cr);
        difference.setText(diff.signum() == 0 ? "Balanced."
                : "Difference of " + AmountFormat.format(diff.abs()) + " goes to Opening Balance Equity ("
                + (diff.signum() > 0 ? "credit" : "debit") + ").");
    }

    private void post() {
        List<OpeningBalance> balances = new ArrayList<>();
        for (Line l : lines.values()) {
            if (!l.debit().commit() || !l.credit().commit()) {
                say(l.account().name() + ": " + (l.debit().errorProperty().get() != null
                        ? l.debit().errorProperty().get() : l.credit().errorProperty().get()));
                return;
            }
            BigDecimal dr = nz(l.debit().getValue());
            BigDecimal cr = nz(l.credit().getValue());
            if (dr.signum() > 0 && cr.signum() > 0) {
                say(l.account().name() + " has both a debit and a credit; enter one.");
                return;
            }
            if (dr.signum() > 0 || cr.signum() > 0) {
                balances.add(new OpeningBalance(l.account().id(), dr, cr));
            }
        }
        if (balances.isEmpty() && (position.openingStock() == null)) {
            say("Enter at least one opening balance.");
            return;
        }
        boolean replace = position.posted();
        String why = reason.getText();
        if (replace && why.isBlank()) {
            say("Say why the opening balances are being replaced; it stays on the record.");
            reason.requestFocus();
            return;
        }
        context.background(replace ? "Replacing the opening balances" : "Posting the opening balances",
                () -> replace ? years.replaceOpening(balances, why, null) : years.postOpening(balances), r -> {
            reason.clear();
            say((replace ? "Opening balances replaced." : "Opening balances posted.")
                    + (r.balanced() ? "" : " " + AmountFormat.format(r.difference().abs())
                    + " went to Opening Balance Equity."));
            load();
        });
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Label bold(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private void say(String text) {
        message.setText(text);
        context.notify(text);
    }
}
