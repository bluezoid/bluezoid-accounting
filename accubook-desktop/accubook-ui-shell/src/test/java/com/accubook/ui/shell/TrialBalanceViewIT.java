package com.accubook.ui.shell;

import com.accubook.accounting.chart.AccountNature;
import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceRow;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trial balance screen against fixed data, so each check is about the
 * screen rather than the books. The end-to-end path is in
 * {@link ShellApplicationIT}.
 */
@DisplayName("Trial balance view")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class TrialBalanceViewIT extends ApplicationTest {

    private static final FiscalYear FY25 = new FiscalYear("fy25", "2025-26",
            LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31), true);
    private static final FiscalYear FY26 = new FiscalYear("fy26", "2026-27",
            LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31), false);

    private static TrialBalanceRow row(String name, AccountNature nature, String dr, String cr, boolean active) {
        return new TrialBalanceRow(name, name, nature, new BigDecimal(dr), new BigDecimal(cr), active);
    }

    /** Balanced in 2026-27; in 2025-26 deliberately out of balance by 0.01. */
    private static final Map<String, TrialBalance> BOOKS = Map.of(
            "fy26", new TrialBalance("fy26", List.of(
                    row("Cash in Hand", AccountNature.ASSETS, "1250000", "0", true),
                    row("Capital", AccountNature.EQUITY, "0", "1250000", true),
                    row("Old Bank", AccountNature.ASSETS, "0", "0", false),
                    row("Round Off", AccountNature.EXPENSES, "0", "0", true)),
                    new BigDecimal("1250000"), new BigDecimal("1250000")),
            "fy25", new TrialBalance("fy25", List.of(
                    row("Cash in Hand", AccountNature.ASSETS, "100.01", "0", true),
                    row("Dormant Debtor", AccountNature.ASSETS, "0", "100", false)),
                    new BigDecimal("100.01"), new BigDecimal("100")));

    @Override
    public void start(Stage stage) {
        TrialBalanceView view = new TrialBalanceView(new TrialBalanceView.Source() {
            @Override
            public List<FiscalYear> fiscalYears() {
                return List.of(FY26, FY25);
            }

            @Override
            public TrialBalance trialBalance(String fiscalYearId) {
                return BOOKS.get(fiscalYearId);
            }
        }, LocalDate.of(2026, 6, 15));
        stage.setScene(new Scene(view, 760, 560));
        stage.show();
    }

    private TableView<TrialBalanceRow> table() {
        return lookup("#trialBalanceTable").queryTableView();
    }

    private String text(String id) {
        return lookup(id).queryAs(Label.class).getText();
    }

    private void waitForStatus(String fragment) throws Exception {
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS,
                () -> text("#trialBalanceStatus").contains(fragment));
    }

    @Test
    @DisplayName("opens on the year containing today, showing only accounts with a balance")
    void opensOnCurrentYear() throws Exception {
        waitForStatus("Balanced");

        ComboBox<FiscalYear> picker = lookup("#fiscalYearPicker").queryComboBox();
        assertThat(picker.getValue()).isEqualTo(FY26);
        assertThat(table().getItems()).extracting(TrialBalanceRow::ledgerName)
                .containsExactly("Cash in Hand", "Capital");
    }

    @Test
    @DisplayName("totals use Indian digit grouping and the report says it balances")
    void totalsAndBalancedStatus() throws Exception {
        waitForStatus("Balanced");

        assertThat(text("#totalDebit")).isEqualTo("12,50,000.00");
        assertThat(text("#totalCredit")).isEqualTo("12,50,000.00");
        assertThat(text("#trialBalanceStatus")).isEqualTo("Balanced - 2026-27");
    }

    @Test
    @DisplayName("accounts with no balance can be shown, and a deactivated one is marked")
    void zeroRowsAndInactiveMarker() throws Exception {
        waitForStatus("Balanced");

        clickOn("#showZeroRows");

        assertThat(table().getItems()).hasSize(4);
        assertThat(table().getColumns().get(0).getCellData(2)).isEqualTo("Old Bank  (inactive)");
    }

    @Test
    @DisplayName("choosing another year reloads; an out-of-balance year is a warning with the difference")
    void outOfBalanceWarning() throws Exception {
        waitForStatus("Balanced");

        interact(() -> lookup("#fiscalYearPicker").queryComboBox().getSelectionModel().select(FY25));
        waitForStatus("OUT OF BALANCE");

        assertThat(text("#trialBalanceStatus")).contains("by 0.01").contains("do not rely");
        // A deactivated account holding a balance stays on the report (A-6).
        assertThat(table().getItems()).extracting(TrialBalanceRow::ledgerName)
                .contains("Dormant Debtor");
    }

    @Test
    @DisplayName("the table cannot be edited")
    void readOnly() throws Exception {
        waitForStatus("Balanced");
        assertThat(table().isEditable()).isFalse();
    }
}
