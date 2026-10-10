package com.accubook.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Receivables or payables at a date, by bucket.
 *
 * <p>Advances are reported beside the buckets, never netted into them: a
 * customer who paid in advance is not "less overdue".
 */
public record Aging(LocalDate asOf, List<OutstandingDocument> documents, List<UnallocatedCredit> credits) {

    public Aging {
        documents = List.copyOf(documents);
        credits = List.copyOf(credits);
    }

    public Map<AgingBucket, BigDecimal> totals() {
        Map<AgingBucket, BigDecimal> totals = new EnumMap<>(AgingBucket.class);
        for (AgingBucket bucket : AgingBucket.values()) {
            totals.put(bucket, BigDecimal.ZERO.setScale(2));
        }
        documents.forEach(d -> totals.merge(d.bucket(), d.outstanding(), BigDecimal::add));
        return totals;
    }

    public BigDecimal totalOutstanding() {
        return documents.stream().map(OutstandingDocument::outstanding).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    public BigDecimal totalCredits() {
        return credits.stream().map(UnallocatedCredit::unallocated).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
