package com.accubook.tax;

/**
 * Where a supply is taxed, and so whether it is intra- or inter-state (P9-5).
 *
 * <p>Resolved once, on the draft, and locked at posting: reports read the
 * stored answer and never recompute it, so a party that later moves state does
 * not re-tax its past invoices.
 *
 * @param placeOfSupply  state code of the place of supply
 * @param supplierState  state code the goods or services are supplied from
 */
public record PlaceOfSupply(String placeOfSupply, String supplierState, SupplyType supplyType) {

    /**
     * A sale. The company supplies from its registered state. The place of
     * supply is, in order: what the user chose; the customer's GSTIN state; the
     * customer's recorded state; the company's own state - an unregistered
     * customer with no address is a counter sale, taxed as one.
     *
     * <p>The reference fails safe to IGST when a state is missing, which
     * mis-taxes every counter sale.
     */
    public static PlaceOfSupply forSale(String companyState, String partyGstin, String partyState, String chosen,
                                        RulePack rules) {
        String place = firstOf(chosen, partyGstin == null ? null : Gstin.stateOf(Gstin.normalise(partyGstin)),
                partyState, companyState);
        return resolve(place, companyState, rules);
    }

    /**
     * A purchase. The vendor supplies from its GSTIN state, else its recorded
     * state, else - an unregistered local supplier - the company's own state.
     * The place of supply is what the user chose, else the company's state,
     * where the goods are received.
     */
    public static PlaceOfSupply forPurchase(String companyState, String partyGstin, String partyState,
                                            String chosen, RulePack rules) {
        String supplier = firstOf(partyGstin == null ? null : Gstin.stateOf(Gstin.normalise(partyGstin)),
                partyState, companyState);
        String place = firstOf(chosen, companyState);
        requireState(supplier, "supplier's state", rules);
        return resolve(place, supplier, rules);
    }

    /** The kind of the place of supply: a union territory without a legislature charges UTGST. */
    public StateCode.Kind placeKind(RulePack rules) {
        return rules.state(placeOfSupply).map(StateCode::kind).orElseThrow();
    }

    private static PlaceOfSupply resolve(String place, String supplier, RulePack rules) {
        requireState(place, "place of supply", rules);
        if ("99".equals(place)) {
            throw new RulePackException("99 (Centre Jurisdiction) is not a place of supply");
        }
        return new PlaceOfSupply(place, supplier,
                place.equals(supplier) ? SupplyType.INTRA_STATE : SupplyType.INTER_STATE);
    }

    private static void requireState(String code, String what, RulePack rules) {
        if (code == null || rules.state(code).isEmpty()) {
            throw new RulePackException("The " + what + " \"" + code + "\" is not a state code in rule pack "
                    + rules.version());
        }
    }

    private static String firstOf(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return null;
    }
}
