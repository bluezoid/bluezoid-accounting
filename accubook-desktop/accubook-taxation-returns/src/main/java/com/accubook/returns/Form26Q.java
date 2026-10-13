package com.accubook.returns;

import java.util.List;

/**
 * A quarter's TDS on non-salary payments (26Q) - or TCS (27EQ) - as data for
 * Protean's Return Preparation Utility (P9-24). The RPU produces the file the
 * FVU validates; this is what goes into it.
 */
public record Form26Q(ReturnPeriod quarter, String kind, String tan, String deductorName,
                      List<TdsDeduction> deductions, List<Challan> challans, List<Gstr1.Issue> issues) {

    public Form26Q {
        deductions = List.copyOf(deductions);
        challans = List.copyOf(challans);
        issues = List.copyOf(issues);
    }

    public boolean complete() {
        return issues.stream().noneMatch(Gstr1.Issue::blocking);
    }
}
