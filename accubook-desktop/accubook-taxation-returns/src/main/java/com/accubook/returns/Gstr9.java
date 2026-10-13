package com.accubook.returns;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GSTR-9, the annual return, tables 4-9 and 17 (P9-22): the year's monthly
 * GSTR-1 and GSTR-3B as the books stand, beside what was filed. Tables 10-16
 * and 18-19 (amendments, demands, refunds, late fees) are for the user.
 *
 * @param b2c                 table 4A: supplies to unregistered persons (B2CL + B2CS)
 * @param b2b                 table 4B: supplies to registered persons
 * @param creditNotes         table 4I: credit notes to registered persons and B2CL notes
 * @param exempt              table 5D
 * @param nilRated            table 5E
 * @param nonGst              table 5F
 * @param itcAsFiled          table 6A: input credit in the GSTR-3Bs filed for the year
 * @param itcInward           table 6B: input credit on bills, as claimed (P9-19)
 * @param itcReverseCharge    table 6C/6D: credit on reverse-charge inward supplies
 * @param itcAs2b             table 8A: input credit the year's GSTR-2Bs show
 * @param taxPayable          table 9: tax payable by component - outward plus reverse charge
 * @param outwardAsFiled      outward taxable value and tax in the GSTR-3Bs filed
 * @param hsn                 table 17: outward supplies by HSN for the year
 */
public record Gstr9(LocalDate from, LocalDate to, String gstin, Gstr3b.Tax b2c, Gstr3b.Tax b2b,
                    Gstr3b.Tax creditNotes, BigDecimal exempt, BigDecimal nilRated, BigDecimal nonGst,
                    Gstr3b.Tax itcAsFiled, Gstr3b.Tax itcInward, Gstr3b.Tax itcReverseCharge, Gstr3b.Tax itcAs2b,
                    Gstr3b.Tax taxPayable, Gstr3b.Tax outwardAsFiled, List<Gstr1.Hsn> hsn,
                    int gstr1Filed, int gstr3bFiled, List<Gstr1.Issue> issues) {

    public Gstr9 {
        hsn = List.copyOf(hsn);
        issues = List.copyOf(issues);
    }

    /** Table 6J: input credit filed less credit as the books now claim it. */
    public Gstr3b.Tax itcDifference() {
        return itcAsFiled.plus(itcInward.plus(itcReverseCharge).negate());
    }

    /** Table 8D: credit in 2B less credit claimed on bills. */
    public Gstr3b.Tax itc2bDifference() {
        return itcAs2b.plus(itcInward.negate());
    }

    public boolean fileable() {
        return issues.stream().noneMatch(Gstr1.Issue::blocking);
    }
}
