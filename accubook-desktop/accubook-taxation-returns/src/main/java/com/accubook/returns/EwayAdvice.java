package com.accubook.returns;

import java.math.BigDecimal;

/**
 * Whether an invoice's goods need an e-way bill (P9-27) - advice, never a
 * refusal: the user knows the consignment, which may combine invoices.
 *
 * @param consignmentValue taxable goods with their GST and cess, exempt and
 *                         non-GST goods left out (rule 138; R-25)
 */
public record EwayAdvice(boolean required, BigDecimal consignmentValue, BigDecimal limit, String reason) {
}
