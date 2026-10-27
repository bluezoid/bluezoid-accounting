package com.accubook.ui.trade;

import com.accubook.module.ModuleId;
import com.accubook.trade.DocumentKind;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.Section;

import java.util.List;

/** The sales side's screens, shown with sales-invoicing. */
public final class SalesScreens implements ScreenProvider {

    public static final ModuleId MODULE = ModuleId.of("sales-invoicing");
    public static final String OPENING_INVOICES = "opening-invoices";

    @Override
    public ModuleId module() {
        return MODULE;
    }

    @Override
    public List<NavigationEntry> entries() {
        return List.of(NavigationEntry.of(OPENING_INVOICES, Section.MASTERS, "Opening invoices (customers)", 70,
                c -> new OpeningBillsScreen(c, DocumentKind.Side.SALES, MODULE)));
    }
}
