package com.accubook.integration.ui;

import com.accubook.accounting.party.Parties;
import com.accubook.accounting.party.Party;
import com.accubook.accounting.party.PartyRole;
import com.accubook.module.EntitlementGuard;
import com.accubook.sales.SalesInvoicing;
import com.accubook.trade.OpeningBill;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.trade.SalesScreens;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Customers' opening invoices entered and cancelled through the screen. */
@DisplayName("Opening invoices screen")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class OpeningBillsScreenIT extends FeatureScreenITBase {

    @Override
    ScreenProvider provider() {
        return new SalesScreens();
    }

    @Override
    String entryId() {
        return SalesScreens.OPENING_INVOICES;
    }

    @Override
    void seed() {
        Parties parties = new Parties(service.openCompanyTransactions());
        parties.create("Mehta Stores", PartyRole.CUSTOMER, null, 0);
        parties.create("Rao Wholesale", PartyRole.VENDOR, null, 0);
    }

    private List<OpeningBill> bills() {
        return new SalesInvoicing(service.openCompanyTransactions(), new EntitlementGuard(service.moduleStates()))
                .openingBills().list();
    }

    @Test
    @DisplayName("an invoice from before the books, by keyboard; dates read as before the books; vendors not offered")
    void enter() throws Exception {
        ComboBox<Party> party = lookup("#billParty").query();
        waitFor(() -> !party.getItems().isEmpty());
        assertThat(party.getItems()).extracting(Party::name).containsExactly("Mehta Stores");
        interact(() -> party.setValue(party.getItems().get(0)));

        clickOn("#billNumber").write("INV-0991").type(KeyCode.ENTER);
        write("10-2").type(KeyCode.ENTER);
        write("12-3").type(KeyCode.ENTER);
        write("11,800").type(KeyCode.ENTER);
        type(KeyCode.ENTER);
        waitForText("#billMessage", "Added INV-0991 for 11,800.00.");
        waitForText("#billTotal", "Outstanding on opening invoices: 11,800.00");

        OpeningBill bill = bills().get(0);
        assertThat(bill.billDate()).isEqualTo(LocalDate.of(2026, 2, 10));
        assertThat(bill.dueDate()).isEqualTo(LocalDate.of(2026, 3, 12));
    }

    @Test
    @DisplayName("cancelled with a reason; without one it is refused")
    void cancel() throws Exception {
        ComboBox<Party> party = lookup("#billParty").query();
        waitFor(() -> !party.getItems().isEmpty());
        interact(() -> party.setValue(party.getItems().get(0)));
        clickOn("#billNumber").write("INV-0992");
        clickOn("#billDate").write("1-3");
        clickOn("#billAmount").write("500");
        clickOn("#addBill");
        waitForText("#billMessage", "Added INV-0992");
        TableView<?> table = lookup("#openingBills").queryTableView();
        waitFor(() -> table.getItems().size() == 1);
        interact(() -> table.getSelectionModel().select(0));

        clickOn("#cancelBill");
        waitForText("#statusBar", "Cancelling INV-0992 failed");
        clickOn("#cancelReason").write("Entered twice");
        clickOn("#cancelBill");
        waitForText("#billMessage", "INV-0992 cancelled.");

        assertThat(bills().get(0).cancelled()).isTrue();
    }
}
