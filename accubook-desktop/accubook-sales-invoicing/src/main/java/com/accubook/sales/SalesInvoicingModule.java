package com.accubook.sales;

import com.accubook.module.FeatureModule;
import com.accubook.module.HistoricalView;
import com.accubook.module.HistoricalView.Column;
import com.accubook.module.HistoricalView.Column.Kind;
import com.accubook.module.ModuleId;

import java.util.List;
import java.util.Set;

/** Identity, table ownership and historical views of the sales-invoicing module. */
public final class SalesInvoicingModule implements FeatureModule {

    public static final ModuleId ID = ModuleId.of("sales-invoicing");

    @Override
    public ModuleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Sales invoicing";
    }

    @Override
    public int navigationOrder() {
        return 10;
    }

    @Override
    public Set<String> ownedTables() {
        return Set.of("sales_document", "sales_document_line", "sales_tax_line");
    }

    /** Migration 6 created the document tables. */
    @Override
    public int requiredSchemaVersion() {
        return 6;
    }

    @Override
    public List<HistoricalView> historicalViews() {
        return List.of(new HistoricalView("Sales documents", "sales_document", List.of(
                new Column("number", "Number", Kind.TEXT),
                new Column("document_date", "Date", Kind.DATE),
                new Column("kind", "Kind", Kind.TEXT),
                new Column("status", "Status", Kind.TEXT),
                new Column("total_e4", "Total", Kind.MONEY)), "document_date", true));
    }
}
