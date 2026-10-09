package com.accubook.trade;

import com.accubook.module.stock.StockPort;

/**
 * The four document kinds and everything that follows from each.
 *
 * <p>{@link #partyDebited} is the whole of D-23. The reference has no branch for
 * a credit note, so a sales return debits the customer and credits sales - it
 * <em>increases</em> revenue and receivables. Here direction is a property of
 * the kind, and a note is defined as the mirror of its parent.
 */
public enum DocumentKind {

    SALES_INVOICE(Side.SALES, "INV", "SALES", StockPort.Kind.SALE, true),
    CREDIT_NOTE(Side.SALES, "CN", "CREDIT_NOTE", StockPort.Kind.SALES_RETURN, false),
    PURCHASE_BILL(Side.PURCHASE, "PB", "PURCHASE", StockPort.Kind.PURCHASE, false),
    DEBIT_NOTE(Side.PURCHASE, "DN", "DEBIT_NOTE", StockPort.Kind.PURCHASE_RETURN, true);

    public enum Side {
        SALES("sales", "SALES"),
        PURCHASE("purchase", "PURCHASES");

        private final String tablePrefix;
        private final String counterLedgerCode;

        Side(String tablePrefix, String counterLedgerCode) {
            this.tablePrefix = tablePrefix;
            this.counterLedgerCode = counterLedgerCode;
        }

        /** {@code sales} or {@code purchase}: the prefix of this side's tables. */
        public String tablePrefix() {
            return tablePrefix;
        }

        /** System code of the Sales or Purchases ledger. */
        public String counterLedgerCode() {
            return counterLedgerCode;
        }
    }

    private final Side side;
    private final String numberPrefix;
    private final String voucherTypeCode;
    private final StockPort.Kind stockKind;
    private final boolean partyDebited;

    DocumentKind(Side side, String numberPrefix, String voucherTypeCode, StockPort.Kind stockKind,
                 boolean partyDebited) {
        this.side = side;
        this.numberPrefix = numberPrefix;
        this.voucherTypeCode = voucherTypeCode;
        this.stockKind = stockKind;
        this.partyDebited = partyDebited;
    }

    public Side side() {
        return side;
    }

    /** INV, CN, PB or DN (P7-8). */
    public String numberPrefix() {
        return numberPrefix;
    }

    /** Code of the voucher type its voucher is posted as. */
    public String voucherTypeCode() {
        return voucherTypeCode;
    }

    /** How posting it moves stock. */
    public StockPort.Kind stockKind() {
        return stockKind;
    }

    /** How cancelling it undoes that movement. */
    public StockPort.Kind cancellationStockKind() {
        return stockKind.bringsStockIn() ? StockPort.Kind.ADJUSTMENT_OUT : StockPort.Kind.ADJUSTMENT_IN;
    }

    /** True when the party's ledger is debited: an invoice, or a debit note to a vendor. */
    public boolean partyDebited() {
        return partyDebited;
    }

    public boolean isNote() {
        return this == CREDIT_NOTE || this == DEBIT_NOTE;
    }

    /** The kind a note corrects; a regular kind is its own parent. */
    public DocumentKind parent() {
        return switch (this) {
            case CREDIT_NOTE -> SALES_INVOICE;
            case DEBIT_NOTE -> PURCHASE_BILL;
            default -> this;
        };
    }

    /** The note that corrects this kind. */
    public DocumentKind note() {
        return side == Side.SALES ? CREDIT_NOTE : DEBIT_NOTE;
    }
}
