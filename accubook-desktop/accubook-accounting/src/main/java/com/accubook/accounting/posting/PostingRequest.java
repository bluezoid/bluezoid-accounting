package com.accubook.accounting.posting;

import com.accubook.module.ModuleId;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A request to post one voucher.
 *
 * <p>Immutable data. It carries no connection and no database handle, so a
 * caller cannot supply its own transaction and post outside the core's control.
 *
 * <h2>Validity is checked here, at construction</h2>
 * An invalid request cannot be built, so it cannot be passed around and posted
 * later by code that assumed someone else had checked. Three conditions matter,
 * and the second is easy to miss:
 *
 * <ol>
 *   <li>debits equal credits, <strong>exactly</strong></li>
 *   <li>the total is greater than zero, and there are at least two entries -
 *       otherwise {@code 0 = 0} satisfies "balanced" and an empty voucher posts
 *       (finding A-1)</li>
 *   <li>every line is one-sided and positive, enforced by {@link EntryLine}</li>
 * </ol>
 *
 * <h2>originator</h2>
 * The module on whose behalf this is posted, used for the entitlement check.
 * It is deliberately <em>not</em> the module that owns the ledger tables: those
 * are kernel-owned, so a payroll downgrade stops new payroll postings without
 * making the trial balance read-only.
 */
public record PostingRequest(
        ModuleId originator,
        String fiscalYearId,
        String voucherTypeId,
        LocalDate voucherDate,
        String referenceNo,
        String narration,
        List<EntryLine> entries,
        String idempotencyKey) {

    public PostingRequest {
        Objects.requireNonNull(originator, "originator");
        Objects.requireNonNull(fiscalYearId, "fiscalYearId");
        Objects.requireNonNull(voucherTypeId, "voucherTypeId");
        Objects.requireNonNull(voucherDate, "voucherDate");
        Objects.requireNonNull(entries, "entries");

        entries = List.copyOf(entries);

        // A-1: 'balanced' is not the same as 'valid'. A voucher with no entries
        // has totals of zero, which satisfies debit == credit and every CHECK
        // constraint in the schema, yet means nothing.
        if (entries.size() < 2) {
            throw new PostingException(
                    "A voucher needs at least two entries; got " + entries.size()
                            + ". A single-sided or empty voucher cannot balance meaningfully.");
        }

        BigDecimal totalDebit = entries.stream()
                .map(EntryLine::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = entries.stream()
                .map(EntryLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalDebit.signum() <= 0) {
            throw new PostingException(
                    "A voucher must move a non-zero amount; totals were zero. "
                            + "0 = 0 balances arithmetically but is not a transaction.");
        }
        // Exact. compareTo rather than equals, so 100.0 and 100.00 agree -
        // that is scale, not value.
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new UnbalancedPostingException(totalDebit, totalCredit);
        }
    }

    /** Sum of debits; equal to {@link #totalCredit()} by construction. */
    public BigDecimal totalDebit() {
        return entries.stream().map(EntryLine::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalCredit() {
        return entries.stream().map(EntryLine::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Whether this request can be safely replayed. */
    public boolean hasIdempotencyKey() {
        return idempotencyKey != null && !idempotencyKey.isBlank();
    }
}
