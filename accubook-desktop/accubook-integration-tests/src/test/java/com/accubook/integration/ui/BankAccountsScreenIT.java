package com.accubook.integration.ui;

import com.accubook.banking.BankAccount;
import com.accubook.banking.Banking;
import com.accubook.module.EntitlementGuard;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.banking.BankingScreens;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/** Bank accounts opened through the screen, by keyboard. */
@DisplayName("Bank accounts screen")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class BankAccountsScreenIT extends FeatureScreenITBase {

    @Override
    ScreenProvider provider() {
        return new BankingScreens();
    }

    @Override
    String entryId() {
        return BankingScreens.ACCOUNTS;
    }

    private Banking banking() {
        return new Banking(service.openCompanyTransactions(), new EntitlementGuard(service.moduleStates()), CLOCK);
    }

    @Test
    @DisplayName("an account opened by keyboard is listed; a bad IFSC is refused and nothing is created")
    void open() throws Exception {
        clickOn("#accountName").write("HDFC Current").type(KeyCode.ENTER);
        write("HDFC Bank").type(KeyCode.ENTER);
        write("Pune").type(KeyCode.ENTER);
        write("50100123456789").type(KeyCode.ENTER);
        write("HDFC0000123").type(KeyCode.ENTER);
        type(KeyCode.ENTER);
        waitForText("#bankMessage", "Opened HDFC Current.");
        TableView<BankAccount> table = lookup("#bankAccounts").queryTableView();
        waitFor(() -> table.getItems().size() == 1);

        clickOn("#accountName").write("Axis Current");
        clickOn("#bankName").write("Axis Bank");
        clickOn("#accountNumber").write("917020012345678");
        clickOn("#ifsc").write("UTIB123");
        clickOn("#openAccount");
        waitForText("#statusBar", "Opening the bank account failed");

        assertThat(banking().accounts()).extracting(BankAccount::ledgerName).containsExactly("HDFC Current");
    }
}
