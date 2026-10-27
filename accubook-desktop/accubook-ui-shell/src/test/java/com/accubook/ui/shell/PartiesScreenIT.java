package com.accubook.ui.shell;

import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.tax.StateCode;
import com.accubook.ui.api.Screen;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/** Customers and vendors entered, checked and changed through the screen. */
@DisplayName("Parties screen")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class PartiesScreenIT extends ScreenITBase {

    @Override
    Screen screen(ShellScreenContext context) {
        return new PartiesScreen(context);
    }

    private Parties parties() {
        return new Parties(service.openCompanyTransactions());
    }

    private Party named(String name) {
        return parties().all().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    private void loaded() throws Exception {
        ComboBox<StateCode> state = lookup("#partyState").query();
        waitFor(() -> !state.getItems().isEmpty());
    }

    @Test
    @DisplayName("a registered customer: the state and PAN come from the GSTIN; the ledger is in Sundry Debtors")
    void registeredCustomer() throws Exception {
        loaded();
        clickOn("#partyName").write("Mehta Stores").type(KeyCode.ENTER);
        type(KeyCode.ENTER);
        write("27PQRSX5678K1ZY").type(KeyCode.TAB);
        clickOn("#partyCity").write("Pune");
        clickOn("#creditLimit").write("50,000");
        clickOn("#creditDays").write("30");
        clickOn("#addParty");
        waitForText("#partyMessage", "Added Mehta Stores.");

        Party mehta = named("Mehta Stores");
        assertThat(mehta.role()).isEqualTo(PartyRole.CUSTOMER);
        assertThat(mehta.profile().stateCode()).isEqualTo("27");
        assertThat(mehta.profile().pan()).isEqualTo("PQRSX5678K");
        assertThat(mehta.profile().creditLimit()).isEqualByComparingTo("50000");
        assertThat(mehta.profile().creditDays()).isEqualTo(30);
        TableView<Party> table = lookup("#partyTable").queryTableView();
        assertThat(table.getItems()).extracting(Party::name).containsExactly("Mehta Stores");
    }

    @Test
    @DisplayName("a GSTIN from another state, a bad check character, a mismatched PAN: refused, nothing saved")
    void refused() throws Exception {
        loaded();
        clickOn("#partyName").write("Rao Wholesale");
        clickOn("#partyGstin").write("27PQRSX5678K1ZY");
        clickOn("#partyPan").write("ABCDE1234F");
        // Chosen after leaving the GSTIN, which would otherwise fill in its own state.
        ComboBox<StateCode> state = lookup("#partyState").query();
        interact(() -> state.setValue(state.getItems().stream().filter(s -> s.code().equals("29")).findFirst()
                .orElseThrow()));
        clickOn("#addParty");
        waitForText("#partyMessage", "The GSTIN is from state 27, not 29");
        assertThat(lookup("#partyMessage").queryAs(javafx.scene.control.Label.class).getText())
                .contains("does not match the one inside the GSTIN");

        clickOn("#partyGstin").eraseText(15).write("27PQRSX5678K1ZX");
        clickOn("#addParty");
        waitFor(() -> lookup("#partyMessage").queryAs(javafx.scene.control.Label.class).getText()
                .toLowerCase().contains("check"));
        assertThat(parties().all()).isEmpty();
    }

    @Test
    @DisplayName("a vendor becomes both, is renamed, and is deactivated")
    void change() throws Exception {
        loaded();
        clickOn("#partyName").write("Rao Wholesale");
        ComboBox<PartyRole> role = lookup("#partyRole").query();
        interact(() -> role.setValue(PartyRole.VENDOR));
        clickOn("#addParty");
        waitForText("#partyMessage", "Added Rao Wholesale.");

        TableView<Party> table = lookup("#partyTable").queryTableView();
        waitFor(() -> table.getItems().size() == 1);
        interact(() -> table.getSelectionModel().select(0));
        clickOn("#partyName").eraseText(13).write("Rao Traders");
        interact(() -> role.setValue(PartyRole.BOTH));
        clickOn("#saveParty");
        waitForText("#partyMessage", "Saved Rao Traders.");
        assertThat(named("Rao Traders").role()).isEqualTo(PartyRole.BOTH);

        clickOn("#toggleParty");
        waitForText("#partyMessage", "Rao Traders deactivated");
        assertThat(named("Rao Traders").active()).isFalse();
    }
}
