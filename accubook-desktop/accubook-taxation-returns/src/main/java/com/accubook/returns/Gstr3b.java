package com.accubook.returns;

import java.math.BigDecimal;
import java.util.List;

/**
 * A GSTR-3B for one period, from the figures documents stored when posted.
 *
 * <p>Input tax credit on bills (table 4(A)(5)) is <b>only what GSTR-2B shows</b>
 * (s.16(2)(aa), P9-19): per bill matched in the month's 2B, the lower of books
 * and 2B; bills the supplier has not reported are deferred. Without the
 * month's 2B imported, the credit shown is as per books and the return cannot
 * be filed. Reverse-charge credit comes from the books. Blocked credit
 * (s.17(5)) is not identified, so table 4(B) is nil.
 *
 * @param itcDeferred credit on bills in the books but not yet in any 2B
 */
public record Gstr3b(ReturnPeriod period, String gstin, String rulePackVersion, Tax outward,
                     BigDecimal nilExemptOutward, BigDecimal nonGstOutward, Tax reverseChargeInward,
                     List<UnregisteredInterState> interStateUnregistered, Tax itcReverseCharge, Tax itcOther,
                     Tax itcDeferred, Inward exemptInward, SetOff setOff, List<Gstr1.Issue> issues) {

    public Gstr3b {
        interStateUnregistered = List.copyOf(interStateUnregistered);
        issues = List.copyOf(issues);
    }

    /** Taxable value and tax by component. {@code sgst} includes UTGST. */
    public record Tax(BigDecimal taxable, BigDecimal igst, BigDecimal cgst, BigDecimal sgst, BigDecimal cess) {
        public static final Tax NIL = new Tax(z(), z(), z(), z(), z());

        /** For GSTR-9 and other sums across returns. */
        public Tax add(Tax o) {
            return plus(o);
        }

        Tax min(Tax o) {
            return new Tax(taxable.min(o.taxable), igst.min(o.igst), cgst.min(o.cgst), sgst.min(o.sgst),
                    cess.min(o.cess));
        }

        Tax max(Tax o) {
            return new Tax(taxable.max(o.taxable), igst.max(o.igst), cgst.max(o.cgst), sgst.max(o.sgst),
                    cess.max(o.cess));
        }

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
