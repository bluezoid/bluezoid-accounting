package com.accubook.trade;

import com.accubook.module.stock.StockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentKindTest {

    @Test
    @DisplayName("each note is the opposite of its parent in party direction and in stock")
    void notesOppose() {
        for (DocumentKind kind : DocumentKind.values()) {
            if (kind.isNote()) {
                DocumentKind parent = kind.parent();
                assertThat(kind.partyDebited()).isNotEqualTo(parent.partyDebited());
                assertThat(kind.stockKind().bringsStockIn()).isNotEqualTo(parent.stockKind().bringsStockIn());
                assertThat(parent.note()).isEqualTo(kind);
                assertThat(kind.side()).isEqualTo(parent.side());
            }
        }
    }

    @Test
    @DisplayName("cancelling undoes the stock effect in the opposite direction")
    void cancellationOpposes() {
        assertThat(DocumentKind.SALES_INVOICE.cancellationStockKind()).isEqualTo(StockPort.Kind.ADJUSTMENT_IN);
        assertThat(DocumentKind.PURCHASE_BILL.cancellationStockKind()).isEqualTo(StockPort.Kind.ADJUSTMENT_OUT);
        assertThat(DocumentKind.CREDIT_NOTE.cancellationStockKind()).isEqualTo(StockPort.Kind.ADJUSTMENT_OUT);
    }
}
