package com.accubook.returns;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A GSTR-1 for one period, built from the figures documents stored when they
 * were posted - never recomputed (P9-5).
 *
 * <p>Every amount is rupees to the paisa. Notes reduce: their values are
 * positive and the section says they are notes; in B2CS, where notes to
 * unregistered buyers are netted, they appear as negative amounts.
 */
public record Gstr1(ReturnPeriod period, String gstin, String rulePackVersion, List<B2b> b2b, List<B2cl> b2cl,
                    List<B2cs> b2cs, List<Cdnr> cdnr, List<Cdnur> cdnur, List<Nil> nil, List<Hsn> hsn,
                    List<DocRange> docs, List<Issue> issues) {

    public Gstr1 {
        b2b = List.copyOf(b2b);
        b2cl = List.copyOf(b2cl);
        b2cs = List.copyOf(b2cs);
        cdnr = List.copyOf(cdnr);
        cdnur = List.copyOf(cdnur);
        nil = List.copyOf(nil);
        hsn = List.copyOf(hsn);
        docs = List.copyOf(docs);
        issues = List.copyOf(issues);
    }

    /** Tax at one rate on one document. {@code sgst} includes UTGST, as the portal reports it. */
    public record RateItem(BigDecimal rate, BigDecimal taxable, BigDecimal igst, BigDecimal cgst, BigDecimal sgst,
                           BigDecimal cess) {
    }

    /** An invoice to a registered customer (table 4A). */
    public record B2b(String ctin, String partyName, String number, LocalDate date, BigDecimal value,
                      String placeOfSupply, boolean reverseCharge, List<RateItem> items) {
    }

    /** An inter-state invoice to an unregistered buyer above the B2CL limit (table 5). */
    public record B2cl(String placeOfSupply, String number, LocalDate date, BigDecimal value, List<RateItem> items) {
    }

    /** Other supplies to unregistered buyers, summed by place of supply and rate (table 7). */
    public record B2cs(boolean interState, String placeOfSupply, BigDecimal rate, BigDecimal taxable,
                       BigDecimal igst, BigDecimal cgst, BigDecimal sgst, BigDecimal cess) {
    }

    /** A credit note to a registered customer (table 9B). */
    public record Cdnr(String ctin, String partyName, String number, LocalDate date, BigDecimal value,
                       String placeOfSupply, List<RateItem> items) {
    }

    /** A credit note against a B2CL invoice (table 9B, unregistered). */
    public record Cdnur(String number, LocalDate date, BigDecimal value, String placeOfSupply,
                        List<RateItem> items) {
    }

    /** Nil-rated, exempt and non-GST supplies (table 8), by inter/intra-state and registered or not. */
    public record Nil(boolean interState, boolean registered, BigDecimal nilRated, BigDecimal exempt,
                      BigDecimal nonGst) {
    }

    /**
     * HSN-wise summary (table 12), split by registered and unregistered buyers.
     *
     * @param uqc {@code OTH} when the unit had none; {@code NA} for services (SAC)
     */
    public record Hsn(boolean registered, String hsn, String uqc, BigDecimal rate, BigDecimal quantity,
                      BigDecimal value, BigDecimal taxable, BigDecimal igst, BigDecimal cgst, BigDecimal sgst,
                      BigDecimal cess) {
    }

    /** Documents issued in the period (table 13): cancelled ones counted, not hidden (R-20). */
    public record DocRange(String nature, String from, String to, int total, int cancelled) {
        public int netIssued() {
            return total - cancelled;
        }
    }

    public record Issue(boolean blocking, String document, String message) {
    }

    /** Whether the return may be marked filed (P9-16). */
    public boolean fileable() {
        return issues.stream().noneMatch(Issue::blocking);
    }
}
