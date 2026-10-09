package com.accubook.accounting.posting;

import com.accubook.module.ModuleId;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A request to reverse a posted voucher.
 *
 * <p>Per P5-2 the reversal is posted into the <strong>current open fiscal
 * period</strong>, which the caller supplies, and the original voucher is never
 * modified - not even when its own period is still open. That uniformity is
 * deliberate: a rule that behaves differently depending on whether a period
 * happens to be closed is a rule people get wrong.
 *
 * <p>A reason is mandatory. A reversal without one is indistinguishable from a
 * mistake, and an auditor asking "why" deserves an answer stored beside the
 * entry rather than in someone's memory.
 *
 * <p>A full prior-period restatement workflow is deliberately <em>not</em> built
 * here. That is an accounting-policy feature; this leaves room for it.
 */
public record ReversalRequest(
        ModuleId originator,
        String fiscalYearId,
        LocalDate reversalDate,
        String reason,
        String postedBy,
        String idempotencyKey) {

    public ReversalRequest {
        Objects.requireNonNull(originator, "originator");
        Objects.requireNonNull(fiscalYearId, "fiscalYearId");
        Objects.requireNonNull(reversalDate, "reversalDate");
        if (reason == null || reason.isBlank()) {
            throw new PostingException(
                    "A reversal must record why. An unexplained reversal cannot be "
                            + "distinguished from an error when the books are audited.");
        }
    }
}
