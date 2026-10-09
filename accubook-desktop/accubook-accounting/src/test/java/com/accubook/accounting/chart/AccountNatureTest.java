package com.accubook.accounting.chart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which side increases an account. Getting this wrong inverts a whole report. */
class AccountNatureTest {

    @Test
    @DisplayName("assets and expenses increase on the debit side")
    void debitNatured() {
        assertAll(
                () -> assertTrue(AccountNature.ASSETS.isDebitNatured()),
                () -> assertTrue(AccountNature.EXPENSES.isDebitNatured()));
    }

    @Test
    @DisplayName("liabilities, income and equity increase on the credit side")
    void creditNatured() {
        assertAll(
                () -> assertFalse(AccountNature.LIABILITIES.isDebitNatured()),
                () -> assertFalse(AccountNature.INCOME.isDebitNatured()),
                () -> assertFalse(AccountNature.EQUITY.isDebitNatured()));
    }

    @ParameterizedTest
    @EnumSource(AccountNature.class)
    @DisplayName("every nature reports a normal side")
    void everyNatureHasASide(AccountNature nature) {
        assertEquals(nature.isDebitNatured() ? "DR" : "CR", nature.normalSide());
    }
}
