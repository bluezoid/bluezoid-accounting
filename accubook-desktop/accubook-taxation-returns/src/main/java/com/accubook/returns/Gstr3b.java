package com.accubook.returns;

import java.math.BigDecimal;
import java.util.List;

/**
 * A GSTR-3B for one period, from the figures documents stored when posted.
 *
 * <p>Input tax credit is <b>as per books</b>: purchase bills from registered
 * vendors and reverse-charge bills. Since 2022 the credit claimed must also
 * appear in GSTR-2B; that comparison is the reconciliation (P9d-3), not this
 * return. Blocked credit (s.17(5)) is not identified, so table 4(B) is nil.
 */
public record Gstr3b(ReturnPeriod period, String gstin, String rulePackVersion, Tax outward,
                     BigDecimal nilExemptOutward, BigDecimal nonGstOutward, Tax reverseChargeInward,
                     List<UnregisteredInterState> interStateUnregistered, Tax itcReverseCharge, Tax itcOther,
                     Inward exemptInward, SetOff setOff, List<Gstr1.Issue> issues) {

    public Gstr3b {
        interStateUnregistered = List.copyOf(interStateUnregistered);
        issues = List.copyOf(issues);
    }

    /** Taxable value and tax by component. {@code sgst} includes UTGST. */
    public record Tax(BigDecimal taxable, BigDecimal igst, BigDecimal cgst, BigDecimal sgst, BigDecimal cess) {
        public static final Tax NIL = new Tax(z(), z(), z(), z(), z());

        Tax plus(Tax o) {
            return new Tax(taxable.add(o.taxable), igst.add(o.igst), cgst.add(o.cgst), sgst.add(o.sgst),
                    cess.add(o.cess));
        }

        Tax negate() {
            return new Tax(taxable.negate(), igst.negate(), cgst.negate(), sgst.negate(), cess.negate());
        }

        private static BigDecimal z() {
            return BigDecimal.ZERO.setScale(2);
        }
    }

    /** Table 3.2: inter-state supplies to unregistered persons, by place of supply. */
    public record UnregisteredInterState(String placeOfSupply, BigDecimal taxable, BigDecimal igst) {
    }

    /** Table 5: exempt, nil-rated and non-GST inward supplies. */
    public record Inward(BigDecimal exemptNilInter, BigDecimal exemptNilIntra, BigDecimal nonGstInter,
                         BigDecimal nonGstIntra) {
    }

    /**
     * A suggested set-off (Rule 88A): IGST credit first, against IGST and then
     * CGST and SGST; CGST credit against CGST then IGST; SGST credit against SGST
     * then IGST; cess against cess. Reverse-charge tax is always paid in cash.
     * The set-off itself is made on the portal; this is a guide.
     *
     * @param cash          tax to pay in cash, by component
     * @param carryForward  credit left over, by component
     */
    public record SetOff(Tax liability, Tax credit, Tax cash, Tax carryForward) {
    }

    public Tax totalItc() {
        return itcReverseCharge.plus(itcOther);
    }

    public boolean fileable() {
        return issues.stream().noneMatch(Gstr1.Issue::blocking);
    }
}
