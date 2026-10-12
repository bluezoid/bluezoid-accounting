package com.accubook.returns;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule 88A order of set-off, as GSTR-3B's suggested payment. */
class SetOffTest {

    private static Gstr3b.Tax tax(String igst, String cgst, String sgst) {
        return new Gstr3b.Tax(BigDecimal.ZERO.setScale(2), new BigDecimal(igst).setScale(2),
                new BigDecimal(cgst).setScale(2), new BigDecimal(sgst).setScale(2), BigDecimal.ZERO.setScale(2));
    }

    private static Gstr3b.SetOff setOff(Gstr3b.Tax outward, Gstr3b.Tax rcm, Gstr3b.Tax credit) {
        return Gstr3bBuilder.setOff(outward, rcm, credit);
    }

    @Test
    @DisplayName("IGST credit goes to IGST first, then where CGST or SGST credit falls short")
    void igstFirst() {
        Gstr3b.SetOff s = setOff(tax("100", "50", "50"), tax("0", "0", "0"), tax("120", "10", "40"));

        assertThat(s.cash().igst()).isEqualByComparingTo("0");
        assertThat(s.cash().cgst()).as("50 - 20 IGST - 10 CGST").isEqualByComparingTo("20");
        assertThat(s.cash().sgst()).as("50 - 40 SGST").isEqualByComparingTo("10");
        assertThat(s.carryForward().igst()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CGST credit may pay IGST once CGST is met")
    void cgstAgainstIgst() {
        Gstr3b.SetOff s = setOff(tax("100", "0", "0"), tax("0", "0", "0"), tax("0", "150", "0"));

        assertThat(s.cash().igst()).isEqualByComparingTo("0");
        assertThat(s.carryForward().cgst()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("CGST credit never pays SGST")
    void neverCgstForSgst() {
        Gstr3b.SetOff s = setOff(tax("0", "0", "100"), tax("0", "0", "0"), tax("0", "100", "0"));

        assertThat(s.cash().sgst()).isEqualByComparingTo("100");
        assertThat(s.carryForward().cgst()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("reverse-charge tax is paid in cash, whatever credit there is")
    void reverseChargeInCash() {
        Gstr3b.SetOff s = setOff(tax("0", "0", "0"), tax("50", "0", "0"), tax("1000", "0", "0"));

        assertThat(s.cash().igst()).isEqualByComparingTo("50");
        assertThat(s.liability().igst()).isEqualByComparingTo("50");
        assertThat(s.carryForward().igst()).isEqualByComparingTo("1000");
    }
}
