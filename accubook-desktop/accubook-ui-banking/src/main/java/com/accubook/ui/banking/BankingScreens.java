package com.accubook.ui.banking;

import com.accubook.banking.BankingModule;
import com.accubook.module.ModuleId;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.Section;

import java.util.List;

/** Banking's screens, found by the shell only when banking is in the build. */
public final class BankingScreens implements ScreenProvider {

    public static final String ACCOUNTS = "bank-accounts";

    @Override
    public ModuleId module() {
        return BankingModule.ID;
    }

    @Override
    public List<NavigationEntry> entries() {
        return List.of(NavigationEntry.of(ACCOUNTS, Section.MASTERS, "Bank accounts", 50, BankAccountsScreen::new));
    }
}
