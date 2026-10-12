package com.accubook.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * GST on one line. Pure: no database, no clock.
 *
 * <p>Each component is computed from the line's taxable value and rounded
 * half-up to the paisa on its own (B-4). Intra-state, the rate is split in half
 * between CGST and SGST - UTGST in a union territory without a legislature;
 * inter-state, IGST takes the whole rate. Cess, when the item carries it, is
 * charged in both cases.
 *
 * <p>The reference computed tax on the unrounded line value and summed the
 * unrounded components, so the printed tax and the posted tax could differ by
 * a paisa.
 */
public final class GstCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private GstCalculator() {
    }

    /**
     * Refuse a category and rates that cannot apply on {@code date}: a taxable
     * line needs a slab rate above zero; nil-rated, exempt and non-GST lines
     * carry no rate and no cess.
     */
    public static void requireValid(TaxCategory category, BigDecimal gstRate, BigDecimal cessRate, LocalDate date,
                                    RulePack rules) {
        if (category == null) {
            throw new RulePackException("Every line needs a GST category: taxable, nil-rated, exempt or non-GST");
        }
        BigDecimal rate = gstRate == null ? BigDecimal.ZERO : gstRate;
        BigDecimal cess = cessRate == null ? BigDecimal.ZERO : cessRate;
        if (rate.signum() < 0 || cess.signum() < 0) {
            throw new RulePackException("A tax rate cannot be negative");
        }
        if (rate.stripTrailingZeros().scale() > 4 || cess.stripTrailingZeros().scale() > 4) {
            throw new RulePackException("A tax rate may have at most four decimal places");
        }
        if (!category.taxed()) {
            if (rate.signum() != 0 || cess.signum() != 0) {
                throw new RulePackException("A " + label(category) + " line carries no GST or cess");
            }
            return;
        }
        if (rate.signum() == 0) {
            throw new RulePackException("A taxable line needs a GST rate above 0%; a 0% supply is nil-rated");
        }
        GstSlabs slabs = rules.gstSlabsOn(date);
        if (!slabs.permits(rate)) {
            throw new RulePackException(rate.stripTrailingZeros().toPlainString() + "% is not a GST rate on " + date
                    + " (rule pack " + rules.version() + ": " + slabs.rates().stream()
                    .map(r -> r.stripTrailingZeros().toPlainString() + "%").toList() + ")");
        }
    }

    /**
     * The tax on one line.
     *
     * @param taxable          the line's value after discount, in rupees
     * @param placeOfSupplyKind the kind of the place of supply: decides SGST or UTGST
     * @return no components for an untaxed line
     */
    public static List<LineTax> lineTax(BigDecimal taxable, TaxCategory category, BigDecimal gstRate,
                                        BigDecimal cessRate, SupplyType supplyType,
                                        StateCode.Kind placeOfSupplyKind) {
        if (taxable.signum() < 0) {
            throw new IllegalArgumentException("A taxable value cannot be negative");
        }
        List<LineTax> taxes = new ArrayList<>(3);
        if (!category.taxed()) {
            return taxes;
        }
        if (supplyType == SupplyType.INTER_STATE) {
            taxes.add(component(GstComponent.IGST, gstRate, taxable));
        } else {
            BigDecimal half = gstRate.divide(TWO);
            taxes.add(component(GstComponent.CGST, half, taxable));
            taxes.add(component(placeOfSupplyKind == StateCode.Kind.UNION_TERRITORY
                    ? GstComponent.UTGST : GstComponent.SGST, half, taxable));
        }
        if (cessRate != null && cessRate.signum() > 0) {
            taxes.add(component(GstComponent.CESS, cessRate, taxable));
        }
        return taxes;
    }

    private static LineTax component(GstComponent component, BigDecimal rate, BigDecimal taxable) {
        return new LineTax(component, rate.stripTrailingZeros(),
                taxable.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP));
    }

    private static String label(TaxCategory category) {
        return switch (category) {
            case TAXABLE -> "taxable";
            case NIL_RATED -> "nil-rated";
            case EXEMPT -> "exempt";
            case NON_GST -> "non-GST";
        };
    }
}
