package com.accubook.ui.shell;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.accounting.chart.TrialBalance;
import com.accubook.accounting.chart.TrialBalanceQuery;
import com.accubook.module.ModuleId;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenContext;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.Section;
import javafx.scene.Node;

import java.time.LocalDate;
import java.util.List;

/** The screens of the kernel - accounting - which every build has. */
final class KernelScreens implements ScreenProvider {

    static final String TRIAL_BALANCE = "trial-balance";
    static final String COMPANY_SETTINGS = "company-settings";

    @Override
    public ModuleId module() {
        return ModuleId.ACCOUNTING;
    }

    @Override
    public List<NavigationEntry> entries() {
        return List.of(
                NavigationEntry.of(TRIAL_BALANCE, Section.REPORTS, "Trial balance", 10, KernelScreens::trialBalance),
                NavigationEntry.of(COMPANY_SETTINGS, Section.SETTINGS, "Company and GST", 10,
                        CompanySettingsScreen::new));
    }

    /** P6-4, now a tab. It reads through the context, which cannot run on the UI thread. */
    private static Screen trialBalance(ScreenContext context) {
        TrialBalanceView view = new TrialBalanceView(new TrialBalanceView.Source() {
            @Override
            public List<FiscalYear> fiscalYears() throws Exception {
                return context.transactions().read(TrialBalanceQuery::fiscalYears);
            }

            @Override
            public TrialBalance trialBalance(String fiscalYearId) throws Exception {
                return context.transactions().read(c -> TrialBalanceQuery.compute(c, fiscalYearId));
            }
        }, LocalDate.now(context.clock()));
        return () -> (Node) view;
    }
}
