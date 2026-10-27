package com.accubook.ui.shell;

import com.accubook.accounting.chart.ChartOfAccounts;
import com.accubook.accounting.chart.LedgerAccount;
import com.accubook.ui.api.Screen;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/** The chart of accounts screen: groups and accounts made, renamed and refused through it. */
@DisplayName("Chart of accounts screen")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class ChartScreenIT extends ScreenITBase {

    @Override
    Screen screen(ShellScreenContext context) {
        return new ChartScreen(context);
    }

    private TreeItem<ChartScreen.Entry> find(TreeItem<ChartScreen.Entry> from, String name) {
        for (TreeItem<ChartScreen.Entry> child : from.getChildren()) {
            String text = child.getValue().group() != null ? child.getValue().group().name()
                    : child.getValue().account().name();
            if (name.equals(text)) {
                return child;
            }
            TreeItem<ChartScreen.Entry> deeper = find(child, name);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private void choose(String name) throws Exception {
        TreeView<ChartScreen.Entry> tree = lookup("#chartTree").query();
        waitFor(() -> tree.getRoot() != null && find(tree.getRoot(), name) != null);
        interact(() -> tree.getSelectionModel().select(find(tree.getRoot(), name)));
    }

    private ChartOfAccounts chart() {
        return new ChartOfAccounts(service.openCompanyTransactions());
    }

    @Test
    @DisplayName("a sub-group under Indirect Expenses, then an account in it, by keyboard")
    void groupThenAccount() throws Exception {
        choose("Indirect Expenses");
        clickOn("#newGroupName").write("Office Expenses").type(KeyCode.ENTER);
        waitForText("#chartMessage", "Added group Office Expenses under Indirect Expenses.");
        assertThat(lookup("#selectedEntry").queryAs(Label.class).getText()).isEqualTo("Group: Office Expenses");

        clickOn("#newLedgerName").write("Stationery").type(KeyCode.ENTER);
        waitForText("#chartMessage", "Added account Stationery in Office Expenses.");

        LedgerAccount stationery = chart().allAccounts().stream().filter(a -> a.name().equals("Stationery"))
                .findFirst().orElseThrow();
        assertThat(chart().groups().stream().filter(g -> g.id().equals(stationery.groupId())).findFirst()
                .orElseThrow().name()).isEqualTo("Office Expenses");
    }

    @Test
    @DisplayName("S2-2: an account under Sundry Debtors is refused with the reason")
    void refusedUnderDebtors() throws Exception {
        choose("Sundry Debtors");
        clickOn("#newLedgerName").write("Misc debtor");
        clickOn("#addLedger");
        waitForText("#statusBar", "Adding the account failed");

        assertThat(lookup("#statusBar").queryAs(Label.class).getText()).contains("added as parties");
        assertThat(chart().allAccounts()).noneMatch(a -> a.name().equals("Misc debtor"));
    }

    @Test
    @DisplayName("an account is renamed and deactivated; a system account cannot be deactivated")
    void renameAndDeactivate() throws Exception {
        choose("Indirect Expenses");
        clickOn("#newLedgerName").write("Telephone").type(KeyCode.ENTER);
        waitForText("#chartMessage", "Added account Telephone");

        clickOn("#renameTo").eraseText(9).write("Telephone and Internet").type(KeyCode.ENTER);
        waitForText("#chartMessage", "Renamed to Telephone and Internet.");
        clickOn("#toggleLedger");
        waitForText("#chartMessage", "Telephone and Internet deactivated");
        assertThat(chart().allAccounts().stream().filter(a -> a.name().equals("Telephone and Internet"))
                .findFirst().orElseThrow().active()).isFalse();

        choose("Cash in Hand");
        clickOn("#toggleLedger");
        waitForText("#statusBar", "Deactivating the account failed");
        assertThat(lookup("#statusBar").queryAs(Label.class).getText()).contains("system account");
    }
}
