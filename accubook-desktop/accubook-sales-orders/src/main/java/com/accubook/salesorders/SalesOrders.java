package com.accubook.salesorders;

import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleTransactions;
import com.accubook.module.stock.StockPort;
import com.accubook.trade.DocumentEngine;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.OrderEngine;

import java.time.Clock;
import java.util.Optional;

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

    public SalesOrders(ModuleTransactions transactions, EntitlementGuard entitlement, Optional<StockPort> stock,
                       Clock clock) {
        DocumentEngine documents = new DocumentEngine(DocumentKind.Side.SALES, SALES_INVOICING, transactions,
                entitlement, new TransactionalPosting(entitlement, clock), stock, clock);
        this.orders = new OrderEngine(DocumentKind.Side.SALES, SalesOrdersModule.ID, transactions, entitlement,
                documents, stock, clock);
    }

    /** Quotations (P7-19). */
    public OrderEngine quotationsAndOrders() {
        return orders;
    }
}
