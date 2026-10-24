package com.accubook.app.entitlement;

import com.accubook.module.ModuleId;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who owns every table the shipped schema creates - whether or not the owning
 * module is in this build.
 *
 * <h2>Why ownership cannot come only from installed modules</h2>
 * The schema is universal (ADR-002): a BASIC build has the inventory tables
 * even though it has no inventory module. If ownership were read only from the
 * modules {@code ServiceLoader} finds, those tables would have no owner in a
 * BASIC build, the guard would treat them as kernel, and anything could write
 * them. Ownership is a fact about the schema, so it lives beside it, and a test
 * asserts that every table the migrations create is classified here exactly
 * once.
 *
 * <p>An installed module's {@code ownedTables()} must agree with this
 * catalogue; that is asserted in the module's own tests.
 */
public final class TableOwners {

    /**
     * Kernel tables: never entitlement-gated. The journal is here by design -
     * if a module owned {@code voucher}, a downgrade would stop the trial balance
     * being maintained.
     */
    public static final Set<String> KERNEL = Set.of(
            // company.db
            "company_info", "fiscal_year", "ledger_group", "ledger", "voucher_type",
            "number_counter", "voucher", "voucher_entry", "audit_log", "party", "gst_registration",
            "withholding_entry",
            // app.db
            "company", "app_metadata", "rule_pack");

    private static final Map<String, ModuleId> FEATURE = Map.ofEntries(
            Map.entry("unit", ModuleId.of("inventory")),
            Map.entry("item_category", ModuleId.of("inventory")),
            Map.entry("item", ModuleId.of("inventory")),
            Map.entry("item_tax", ModuleId.of("inventory")),
            Map.entry("warehouse", ModuleId.of("inventory")),
            Map.entry("stock", ModuleId.of("inventory")),
            Map.entry("stock_movement", ModuleId.of("inventory")),
            Map.entry("valuation_entry", ModuleId.of("inventory")),
            Map.entry("fifo_layer", ModuleId.of("inventory")),
            Map.entry("fifo_consumption", ModuleId.of("inventory")),
            Map.entry("valuation_run", ModuleId.of("inventory")),
            Map.entry("sales_document", ModuleId.of("sales-invoicing")),
            Map.entry("sales_document_line", ModuleId.of("sales-invoicing")),
            Map.entry("sales_tax_line", ModuleId.of("sales-invoicing")),
            Map.entry("sales_settlement", ModuleId.of("sales-invoicing")),
            Map.entry("sales_allocation", ModuleId.of("sales-invoicing")),
            Map.entry("purchase_document", ModuleId.of("purchase-billing")),
            Map.entry("purchase_document_line", ModuleId.of("purchase-billing")),
            Map.entry("purchase_tax_line", ModuleId.of("purchase-billing")),
            Map.entry("purchase_settlement", ModuleId.of("purchase-billing")),
            Map.entry("purchase_allocation", ModuleId.of("purchase-billing")),
            Map.entry("gst_return", ModuleId.of("taxation-returns")),
            Map.entry("gstr2b_import", ModuleId.of("taxation-returns")),
            Map.entry("gstr2b_document", ModuleId.of("taxation-returns")),
            Map.entry("tds_challan", ModuleId.of("taxation-returns")),
            Map.entry("tds_challan_entry", ModuleId.of("taxation-returns")),
            Map.entry("einvoice_applicability", ModuleId.of("taxation-returns")),
            Map.entry("einvoice_record", ModuleId.of("taxation-returns")),
            Map.entry("eway_bill_record", ModuleId.of("taxation-returns")),
            Map.entry("sales_quotation", ModuleId.of("sales-orders")),
            Map.entry("sales_quotation_line", ModuleId.of("sales-orders")),
            Map.entry("sales_order", ModuleId.of("sales-orders")),
            Map.entry("sales_order_line", ModuleId.of("sales-orders")),
            Map.entry("purchase_order", ModuleId.of("purchase-orders")),
            Map.entry("purchase_order_line", ModuleId.of("purchase-orders")));

    private TableOwners() {
    }

    public static boolean isKernel(String table) {
        return KERNEL.contains(table);
    }

    /** The feature module that owns {@code table}, installed or not. */
    public static Optional<ModuleId> featureOwnerOf(String table) {
        return Optional.ofNullable(FEATURE.get(table));
    }

    /** Every table owned by {@code module}. */
    public static Set<String> tablesOf(ModuleId module) {
        Set<String> tables = new TreeSet<>();
        FEATURE.forEach((table, owner) -> {
            if (owner.equals(module)) {
                tables.add(table);
            }
        });
        return tables;
    }

    /** Every classified table. */
    public static Set<String> all() {
        Set<String> tables = new TreeSet<>(KERNEL);
        tables.addAll(FEATURE.keySet());
        return tables;
    }
}
