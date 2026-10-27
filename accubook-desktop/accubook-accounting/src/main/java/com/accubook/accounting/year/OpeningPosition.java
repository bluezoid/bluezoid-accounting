package com.accubook.accounting.year;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The opening balances as they stand, for a screen to show and edit.
 *
 * @param booksBegin   the first day of the first year
 * @param posted       whether an opening voucher is in force
 * @param lines        its lines as entered: not the Stock-in-Hand line, which is computed, nor the balancing
 *                     line to Opening Balance Equity
 * @param openingStock the item-wise opening stock's value (S2-5), or null when none is entered
 * @param editable     false once the first year is closed (S2-3)
 */
public record OpeningPosition(LocalDate booksBegin, boolean posted, List<OpeningBalance> lines,
                              BigDecimal openingStock, boolean editable) {
}
