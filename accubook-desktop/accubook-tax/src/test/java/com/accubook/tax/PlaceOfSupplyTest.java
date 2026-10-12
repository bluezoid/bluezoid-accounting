package com.accubook.tax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaceOfSupplyTest {

    private static final RulePack RULES = RulePacks.bundled(TrustedRulePackKeys.forThisBuild()).pack();
    private static final String MAHARASHTRA = "27";
    private static final String KARNATAKA = "29";

    private static String gstin(String first14) {
        return first14 + Gstin.checkCharacter(first14);
    }

    @Test
    @DisplayName("a sale to a registered customer in another state is inter-state, placed by its GSTIN")
    void registeredCustomerElsewhere() {
        PlaceOfSupply place = PlaceOfSupply.forSale(MAHARASHTRA, gstin("29ABCDE1234F1Z"), "27", null, RULES);

        assertThat(place).isEqualTo(new PlaceOfSupply(KARNATAKA, MAHARASHTRA, SupplyType.INTER_STATE));
    }

    @Test
    @DisplayName("an unregistered customer is placed by its recorded state")
    void unregisteredWithState() {
        assertThat(PlaceOfSupply.forSale(MAHARASHTRA, null, KARNATAKA, null, RULES).supplyType())
                .isEqualTo(SupplyType.INTER_STATE);
    }

    @Test
    @DisplayName("P9-5: a counter sale with no state is intra-state, not the reference's fail-safe IGST")
    void counterSale() {
        assertThat(PlaceOfSupply.forSale(MAHARASHTRA, null, null, null, RULES))
                .isEqualTo(new PlaceOfSupply(MAHARASHTRA, MAHARASHTRA, SupplyType.INTRA_STATE));
    }

    @Test
    @DisplayName("a place the user chose wins over the party's")
    void chosen() {
        assertThat(PlaceOfSupply.forSale(MAHARASHTRA, gstin("29ABCDE1234F1Z"), null, MAHARASHTRA, RULES).supplyType())
                .isEqualTo(SupplyType.INTRA_STATE);
    }

    @Test
    @DisplayName("a purchase from a vendor in another state is inter-state; from an unknown local vendor, intra")
    void purchases() {
        assertThat(PlaceOfSupply.forPurchase(MAHARASHTRA, gstin("29ABCDE1234F1Z"), null, null, RULES))
                .isEqualTo(new PlaceOfSupply(MAHARASHTRA, KARNATAKA, SupplyType.INTER_STATE));
        assertThat(PlaceOfSupply.forPurchase(MAHARASHTRA, null, null, null, RULES).supplyType())
                .isEqualTo(SupplyType.INTRA_STATE);
    }

    @Test
    @DisplayName("a union territory without a legislature is told apart from a state")
    void placeKind() {
        assertThat(PlaceOfSupply.forSale("38", null, null, null, RULES).placeKind(RULES))
                .isEqualTo(StateCode.Kind.UNION_TERRITORY);
        assertThat(PlaceOfSupply.forSale("07", null, null, null, RULES).placeKind(RULES))
                .as("Delhi has a legislature").isEqualTo(StateCode.Kind.STATE);
    }

    @Test
    @DisplayName("an unknown state and Centre Jurisdiction are refused as places of supply")
    void refused() {
        assertThatThrownBy(() -> PlaceOfSupply.forSale(MAHARASHTRA, null, "55", null, RULES))
                .hasMessageContaining("\"55\" is not a state code");
        assertThatThrownBy(() -> PlaceOfSupply.forSale(MAHARASHTRA, null, null, "99", RULES))
                .hasMessageContaining("not a place of supply");
    }
}
