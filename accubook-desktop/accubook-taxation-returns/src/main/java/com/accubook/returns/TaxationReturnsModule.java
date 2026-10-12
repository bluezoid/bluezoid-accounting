package com.accubook.returns;

import com.accubook.module.FeatureModule;
import com.accubook.module.HistoricalView;
import com.accubook.module.HistoricalView.Column;
import com.accubook.module.HistoricalView.Column.Kind;
import com.accubook.module.ModuleId;

import java.util.List;
import java.util.Set;

/** Identity, dependencies, table ownership and historical views of taxation-returns. */
public final class TaxationReturnsModule implements FeatureModule {

    public static final ModuleId ID = ModuleId.of("taxation-returns");

    @Override
    public ModuleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Tax returns";
    }

    @Override
    public int navigationOrder() {
        return 40;
    }

    /** Returns are made from invoices and bills (B-7). */
    @Override
    public Set<ModuleId> dependsOn() {
        return Set.of(ModuleId.of("sales-invoicing"), ModuleId.of("purchase-billing"));
    }

    @Override
    public Set<String> ownedTables() {
        return Set.of("gst_return");
    }

    /** Migration 13 created gst_return. */
    @Override
    public int requiredSchemaVersion() {
        return 13;
    }

    @Override
    public List<HistoricalView> historicalViews() {
        return List.of(new HistoricalView("Filed GST returns", "gst_return", List.of(
                new Column("return_type", "Return", Kind.TEXT),
                new Column("period_from", "From", Kind.DATE),
                new Column("period_to", "To", Kind.DATE),
                new Column("filed_on", "Filed on", Kind.DATE),
                new Column("acknowledgement", "ARN", Kind.TEXT),
                new Column("status", "Status", Kind.TEXT)), "period_from", true));
    }
}
