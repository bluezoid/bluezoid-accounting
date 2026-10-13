package com.accubook.returns;

/** The result of importing a GSTR-2B file. */
public record Gstr2bImported(String importId, ReturnPeriod period, int documents, boolean supersededEarlier) {
}
