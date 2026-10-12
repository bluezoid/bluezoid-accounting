package com.accubook.trade;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * What a draft says. Totals and tax are never part of it: they are computed
 * from the lines.
 *
 * @param dueDate            null to derive it from the party's credit days
 * @param originalDocumentId required for a note (P7-11), refused otherwise
 * @param roundOff           round the total to the rupee (P7-10, on by default)
 * @param supplierDocumentNo the supplier's own bill number; purchase side only
 * @param quotationId        the quotation an invoice was made from (P7-19); sales only
 * @param placeOfSupply      state code, or null to default it from the party (P9-5);
 *                           a note always takes its original's
 * @param reverseCharge      purchase only: the company pays this bill's GST itself
 */
public record DraftRequest(DocumentKind kind, String partyId, LocalDate date, LocalDate dueDate,
                           String originalDocumentId, boolean roundOff, String supplierDocumentNo,
                           String narration, List<LineRequest> lines, String quotationId, String placeOfSupply,
                           boolean reverseCharge) {

    public DraftRequest(DocumentKind kind, String partyId, LocalDate date, LocalDate dueDate,
                        String originalDocumentId, boolean roundOff, String supplierDocumentNo,
                        String narration, List<LineRequest> lines, String quotationId) {
        this(kind, partyId, date, dueDate, originalDocumentId, roundOff, supplierDocumentNo, narration, lines,
                quotationId, null, false);
    }

    public DraftRequest(DocumentKind kind, String partyId, LocalDate date, LocalDate dueDate,
                        String originalDocumentId, boolean roundOff, String supplierDocumentNo,
                        String narration, List<LineRequest> lines) {
        this(kind, partyId, date, dueDate, originalDocumentId, roundOff, supplierDocumentNo, narration, lines, null);
    }

    public DraftRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(date, "date");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static DraftRequest of(DocumentKind kind, String partyId, LocalDate date, List<LineRequest> lines) {
        return new DraftRequest(kind, partyId, date, null, null, true, null, null, lines);
    }

    public static DraftRequest note(DocumentKind kind, String partyId, LocalDate date, String originalDocumentId,
                                    List<LineRequest> lines) {
        return new DraftRequest(kind, partyId, date, null, originalDocumentId, true, null, null, lines);
    }

    public DraftRequest withRoundOff(boolean enabled) {
        return new DraftRequest(kind, partyId, date, dueDate, originalDocumentId, enabled, supplierDocumentNo,
                narration, lines, quotationId, placeOfSupply, reverseCharge);
    }

    public DraftRequest withSupplierDocumentNo(String number) {
        return new DraftRequest(kind, partyId, date, dueDate, originalDocumentId, roundOff, number,
                narration, lines, quotationId, placeOfSupply, reverseCharge);
    }

    public DraftRequest withQuotation(String id) {
        return new DraftRequest(kind, partyId, date, dueDate, originalDocumentId, roundOff, supplierDocumentNo,
                narration, lines, id, placeOfSupply, reverseCharge);
    }

    public DraftRequest withPlaceOfSupply(String stateCode) {
        return new DraftRequest(kind, partyId, date, dueDate, originalDocumentId, roundOff, supplierDocumentNo,
                narration, lines, quotationId, stateCode, reverseCharge);
    }

    public DraftRequest withReverseCharge(boolean enabled) {
        return new DraftRequest(kind, partyId, date, dueDate, originalDocumentId, roundOff, supplierDocumentNo,
                narration, lines, quotationId, placeOfSupply, enabled);
    }
}
