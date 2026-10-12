package com.accubook.salesorders;

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
 * Sales orders use cases. Converting an order creates a draft through the
 * shared document engine, entitled as {@code sales-invoicing} - the module that
 * owns the documents - so a customer without that module cannot invoice from
 * an order either.
 */
public final class SalesOrders {

    private static final ModuleId SALES_INVOICING = ModuleId.of("sales-invoicing");

    private final OrderEngine orders;

    public SalesOrders(ModuleTransactions transactions, EntitlementGuard entitlement) {
        this(transactions, entitlement, StockPort.installed(), Clock.systemUTC());
    }

    /** Over the rule pack bundled with this build - for tests and tools. */
    public SalesOrders(ModuleTransactions transactions, EntitlementGuard entitlement, Optional<StockPort> stock,
                       Clock clock) {
        this(transactions, entitlement, stock, RulePacks::bundledPack, clock);
    }

    /** @param rules the active rule pack - in the application, {@code CompanyService::rules} */
    public SalesOrders(ModuleTransactions transactions, EntitlementGuard entitlement, Optional<StockPort> stock,
                       Supplier<RulePack> rules, Clock clock) {
        DocumentEngine documents = new DocumentEngine(DocumentKind.Side.SALES, SALES_INVOICING, transactions,
                entitlement, new TransactionalPosting(entitlement, clock), stock, rules, clock);
        this.orders = new OrderEngine(DocumentKind.Side.SALES, SalesOrdersModule.ID, transactions, entitlement,
                documents, stock, clock);
    }

    /** Quotations (P7-19). */
    public OrderEngine quotationsAndOrders() {
        return orders;
    }
}
