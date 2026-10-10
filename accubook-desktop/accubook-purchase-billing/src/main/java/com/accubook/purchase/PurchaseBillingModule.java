package com.accubook.purchase;

import com.accubook.module.FeatureModule;
import com.accubook.module.HistoricalView;
import com.accubook.module.HistoricalView.Column;
import com.accubook.module.HistoricalView.Column.Kind;
import com.accubook.module.ModuleId;

import java.util.List;
import java.util.Set;

/** Identity, table ownership and historical views of the purchase-billing module. */
public final class PurchaseBillingModule implements FeatureModule {

    public static final ModuleId ID = ModuleId.of("purchase-billing");

    @Override
    public ModuleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Purchase billing";
    }

    @Override
    public int navigationOrder() {
        return 20;
    }

    @Override
    public Set<String> ownedTables() {
        return Set.of("purchase_document", "purchase_document_line", "purchase_tax_line",
                "purchase_settlement", "purchase_allocation");
    }

    /** Migration 7 completed this module's tables. */
    @Override
    public int requiredSchemaVersion() {
        return 7;
    }

    @Override
    public List<HistoricalView> historicalViews() {
        return List.of(new HistoricalView("Purchase documents", "purchase_document", List.of(
                new Column("number", "Number", Kind.TEXT),
                new Column("document_date", "Date", Kind.DATE),
                new Column("kind", "Kind", Kind.TEXT),
                new Column("status", "Status", Kind.TEXT),
                new Column("total_e4", "Total", Kind.MONEY)), "document_date", true));
    }
}
