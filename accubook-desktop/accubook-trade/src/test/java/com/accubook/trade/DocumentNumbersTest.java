package com.accubook.trade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentNumbersTest {

    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);
    private static final LocalDate MARCH = LocalDate.of(2027, 3, 31);

    @Test
    @DisplayName("P7-8: INV/26-27/00001, fifteen characters")
    void format() {
        String number = DocumentNumbers.format(DocumentKind.SALES_INVOICE, APRIL, MARCH, 1);
        assertThat(number).isEqualTo("INV/26-27/00001").hasSize(15);
        assertThat(DocumentNumbers.format(DocumentKind.CREDIT_NOTE, APRIL, MARCH, 42)).isEqualTo("CN/26-27/00042");
        assertThat(DocumentNumbers.format(DocumentKind.PURCHASE_BILL, APRIL, MARCH, 7)).isEqualTo("PB/26-27/00007");
        assertThat(DocumentNumbers.format(DocumentKind.DEBIT_NOTE, APRIL, MARCH, 7)).isEqualTo("DN/26-27/00007");
    }

    @Test
    @DisplayName("R-10: never more than Rule 46(b)'s sixteen characters")
    void ruleFortySixB() {
        assertThat(DocumentNumbers.format(DocumentKind.SALES_INVOICE, APRIL, MARCH, 999_999)).hasSize(16);
        assertThatThrownBy(() -> DocumentNumbers.format(DocumentKind.SALES_INVOICE, APRIL, MARCH, 1_000_000))
                .isInstanceOf(TradeException.class).hasMessageContaining("16-character");
    }

    @Test
    @DisplayName("a calendar-year fiscal year is labelled by its one year")
    void calendarYear() {
        assertThat(DocumentNumbers.format(DocumentKind.SALES_INVOICE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 3)).isEqualTo("INV/26/00003");
    }

    @Test
    @DisplayName("a century turn keeps two digits")
    void centuryTurn() {
        assertThat(DocumentNumbers.yearLabel(LocalDate.of(2099, 4, 1), LocalDate.of(2100, 3, 31))).isEqualTo("99-00");
    }
}
