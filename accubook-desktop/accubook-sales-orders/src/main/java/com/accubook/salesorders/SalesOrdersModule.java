package com.accubook.salesorders;

import com.accubook.module.FeatureModule;
import com.accubook.module.HistoricalView;
import com.accubook.module.HistoricalView.Column;
import com.accubook.module.HistoricalView.Column.Kind;
import com.accubook.module.ModuleId;

import java.util.List;
import java.util.Set;

/** Identity, dependencies, table ownership and historical views of sales-orders. */
public final class SalesOrdersModule implements FeatureModule {

    public static final ModuleId ID = ModuleId.of("sales-orders");

    @Override
    public ModuleId id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Sales orders";
    }

    @Override
    public int navigationOrder() {
        return 15;
    }

    /** Order lines name items: B-7 (order item ids are mandatory). */
    @Override
    public Set<ModuleId> dependsOn() {
        return Set.of(ModuleId.of("inventory"));
    }

    @Override
    public Set<String> ownedTables() {
        return Set.of("sales_quotation", "sales_quotation_line", "sales_order", "sales_order_line");
    }

    /** Migration 8 created these tables. */
    @Override
    public int requiredSchemaVersion() {
        return 8;
    }

    @Override
    public List<HistoricalView> historicalViews() {
        return List.of(new HistoricalView("Quotations", "sales_quotation", List.of(
                        new Column("number", "Number", Kind.TEXT),
                        new Column("quotation_date", "Date", Kind.DATE),
                        new Column("valid_until", "Valid until", Kind.DATE),
                        new Column("status", "Status", Kind.TEXT),
                        new Column("amount_e4", "Amount", Kind.MONEY)), "quotation_date", true),
                new HistoricalView("Sales orders", "sales_order", List.of(
                        new Column("number", "Number", Kind.TEXT),
                        new Column("order_date", "Date", Kind.DATE),
                        new Column("status", "Status", Kind.TEXT),
                        new Column("amount_e4", "Amount", Kind.MONEY)), "order_date", true));
    }
}
