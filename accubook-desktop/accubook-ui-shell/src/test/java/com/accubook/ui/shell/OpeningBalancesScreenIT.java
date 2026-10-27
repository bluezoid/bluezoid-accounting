package com.accubook.ui.shell;

import com.accubook.accounting.year.FiscalYears;
import com.accubook.accounting.year.OpeningPosition;
import com.accubook.module.EntitlementGuard;
import com.accubook.ui.api.Screen;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/** Opening balances posted and replaced through the screen. */
@DisplayName("Opening balances screen")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class OpeningBalancesScreenIT extends ScreenITBase {

    @Override
    Screen screen(ShellScreenContext context) {
        return new OpeningBalancesScreen(context);
    }

    private OpeningPosition position() {
        return new FiscalYears(service.openCompanyTransactions(), new EntitlementGuard(service.moduleStates()), CLOCK)
                .openingPosition();
    }

    @Test
    @DisplayName("cash posted with the difference shown going to Opening Balance Equity; then replaced, with a reason")
    void postThenReplace() throws Exception {
        waitForText("#openingHeading", "Opening balances on 01-04-2026 - not yet posted");
        assertThat(lookup("#dr-OpeningBalanceEquity").tryQuery()).as("the balancing account is not typed").isEmpty();
        clickOn("#dr-CashinHand").write("1,00,000").type(KeyCode.TAB);
        waitForText("#openingDifference", "Difference of 1,00,000.00 goes to Opening Balance Equity (credit).");

        clickOn("#postOpening");
        waitForText("#openingMessage", "Opening balances posted. 1,00,000.00 went to Opening Balance Equity.");
        waitForText("#openingHeading", "Opening balances on 01-04-2026 - posted");

        clickOn("#dr-CashinHand").write("90000").type(KeyCode.TAB);
        clickOn("#postOpening");
        waitForText("#openingMessage", "Say why");
        clickOn("#replaceReason").write("Cash recounted");
        clickOn("#postOpening");
        waitForText("#openingMessage", "Opening balances replaced.");

        assertThat(position().lines()).singleElement()
                .satisfies(l -> assertThat(l.debit()).isEqualByComparingTo("90000"));
        assertThat(lookup("#openingTotals").queryAs(Label.class).getText()).isEqualTo("Debits 90,000.00   Credits 0.00");
    }
}
