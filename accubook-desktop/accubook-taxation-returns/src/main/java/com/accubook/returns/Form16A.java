package com.accubook.returns;

import java.math.BigDecimal;
import java.util.List;

/** The figures for one deductee's Form 16A for a quarter: what was deducted and where it was deposited. */
public record Form16A(ReturnPeriod quarter, String tan, String deductorName, String partyName, String pan,
                      List<TdsDeduction> deductions) {

    public Form16A {
        deductions = List.copyOf(deductions);
    }

    public BigDecimal totalPaid() {
        return deductions.stream().map(TdsDeduction::base).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal totalDeducted() {
        return deductions.stream().map(TdsDeduction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
