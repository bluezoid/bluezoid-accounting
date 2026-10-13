package com.accubook.returns;

import com.accubook.tax.CompositionCategory;

import java.math.BigDecimal;
import java.util.List;

/**
 * A composition dealer's quarterly CMP-08, from the bills of supply and
 * reverse-charge bills as posted.
 *
 * @param outwardTurnover every outward supply of the quarter, less credit notes
 * @param outwardBase     what the composition rate is charged on: taxable
 *                        supplies only for a trader (R-24), the turnover otherwise
 * @param compositionCgst half the composition tax, rounded half-up to the paisa
 * @param compositionSgst the other half
 * @param reverseCharge   inward supplies under reverse charge, taxed at normal rates
 */
public record Cmp08(ReturnPeriod period, String gstin, CompositionCategory category, String rulePackVersion,
                    BigDecimal outwardTurnover, BigDecimal outwardBase, BigDecimal compositionCgst,
                    BigDecimal compositionSgst, Gstr3b.Tax reverseCharge, List<Gstr1.Issue> issues) {

    public Cmp08 {
        issues = List.copyOf(issues);
    }

    public BigDecimal compositionTax() {
        return compositionCgst.add(compositionSgst);
    }

    /** Payable in cash: composition tax plus reverse-charge tax. */
    public BigDecimal totalPayable() {
        return compositionTax().add(reverseCharge.igst()).add(reverseCharge.cgst()).add(reverseCharge.sgst())
                .add(reverseCharge.cess());
    }
}
