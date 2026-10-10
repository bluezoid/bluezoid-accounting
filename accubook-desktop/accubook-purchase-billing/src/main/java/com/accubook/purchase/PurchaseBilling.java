package com.accubook.purchase;

import com.accubook.accounting.posting.TransactionalPosting;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleTransactions;
import com.accubook.module.stock.StockPort;
import com.accubook.trade.Document;
import com.accubook.trade.DocumentEngine;
import com.accubook.trade.DocumentKind;
import com.accubook.trade.DraftRequest;
import com.accubook.trade.SettlementEngine;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Purchase billing use cases. Everything is the shared engine on the
 * purchase side; this class fixes the side, the module identity and the
 * stock port this build has.
 */
public final class PurchaseBilling {

    private final DocumentEngine engine;
    private final SettlementEngine settlements;

    public PurchaseBilling(ModuleTransactions transactions, EntitlementGuard entitlement) {
        this(transactions, entitlement, StockPort.installed(), Clock.systemUTC());
    }

    public PurchaseBilling(ModuleTransactions transactions, EntitlementGuard entitlement, Optional<StockPort> stock,
                         Clock clock) {
        TransactionalPosting posting = new TransactionalPosting(entitlement, clock);
        this.engine = new DocumentEngine(DocumentKind.Side.PURCHASE, PurchaseBillingModule.ID, transactions, entitlement,
                posting, stock, clock);
        this.settlements = new SettlementEngine(DocumentKind.Side.PURCHASE, PurchaseBillingModule.ID, transactions, entitlement,
                posting, clock);
    }

    /** Payments, allocations, outstanding and aging for this side. */
    public SettlementEngine settlements() {
        return settlements;
    }

    public Document createDraft(DraftRequest request) {
        return engine.createDraft(request);
    }

    public Document updateDraft(String documentId, DraftRequest request) {
        return engine.updateDraft(documentId, request);
    }

    public void deleteDraft(String documentId) {
        engine.deleteDraft(documentId);
    }

    public Document post(String documentId) {
        return engine.post(documentId);
    }

    public Document cancel(String documentId, String reason, LocalDate cancellationDate) {
        return engine.cancel(documentId, reason, cancellationDate);
    }

    public Optional<Document> find(String documentId) {
        return engine.find(documentId);
    }

    public List<Document> list() {
        return engine.list();
    }

    /** What a posted purchase bill still has to be corrected against. */
    public BigDecimal uncredited(String documentId) {
        return engine.uncredited(documentId);
    }
}
