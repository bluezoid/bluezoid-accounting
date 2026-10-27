package com.accubook.ui.trade;

import com.accubook.module.ModuleId;
import com.accubook.trade.DocumentKind;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.Section;

import java.util.List;

/** The purchase side's screens, shown with purchase-billing. */
public final class PurchaseScreens implements ScreenProvider {

    public static final ModuleId MODULE = ModuleId.of("purchase-billing");
    public static final String OPENING_BILLS = "opening-bills";

    @Override
    public ModuleId module() {
        return MODULE;
    }

    @Override
    public List<NavigationEntry> entries() {
        return List.of(NavigationEntry.of(OPENING_BILLS, Section.MASTERS, "Opening bills (vendors)", 80,
                c -> new OpeningBillsScreen(c, DocumentKind.Side.PURCHASE, MODULE)));
    }
}
