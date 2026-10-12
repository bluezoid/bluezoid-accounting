package com.accubook.purchaseorders;

import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleTransactions;
import com.accubook.module.stock.StockPort;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePacks;
import com.accubook.trade.DocumentEngine;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.OrderEngine;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Purchase orders use cases. Converting an order creates a draft through the
 * shared document engine, entitled as {@code purchase-billing} - the module that
 * owns the documents - so a customer without that module cannot invoice from
 * an order either.
 */
public final class PurchaseOrders {

    private static final ModuleId PURCHASE_BILLING = ModuleId.of("purchase-billing");

    private final OrderEngine orders;

    public PurchaseOrders(ModuleTransactions transactions, EntitlementGuard entitlement) {
        this(transactions, entitlement, StockPort.installed(), Clock.systemUTC());
    }

    /** Over the rule pack bundled with this build - for tests and tools. */
    public PurchaseOrders(ModuleTransactions transactions, EntitlementGuard entitlement, Optional<StockPort> stock,
                       Clock clock) {
        this(transactions, entitlement, stock, RulePacks::bundledPack, clock);
    }

    /** @param rules the active rule pack - in the application, {@code CompanyService::rules} */
    public PurchaseOrders(ModuleTransactions transactions, EntitlementGuard entitlement, Optional<StockPort> stock,
                       Supplier<RulePack> rules, Clock clock) {
        DocumentEngine documents = new DocumentEngine(DocumentKind.Side.PURCHASE, PURCHASE_BILLING, transactions,
                entitlement, new TransactionalPosting(entitlement, clock), stock, rules, clock);
        this.orders = new OrderEngine(DocumentKind.Side.PURCHASE, PurchaseOrdersModule.ID, transactions, entitlement,
                documents, stock, clock);
    }

    public OrderEngine orders() {
        return orders;
    }
}
