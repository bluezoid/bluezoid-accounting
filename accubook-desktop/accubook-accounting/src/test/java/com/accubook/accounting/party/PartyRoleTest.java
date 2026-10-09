package com.accubook.accounting.party;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartyRoleTest {

    @Test
    @DisplayName("P7-2: customers and parties that are both go under Debtors, vendors under Creditors")
    void defaultPlacement() {
        assertEquals("SUNDRY_DEBTORS", PartyRole.CUSTOMER.defaultGroupCode());
        assertEquals("SUNDRY_DEBTORS", PartyRole.BOTH.defaultGroupCode());
        assertEquals("SUNDRY_CREDITORS", PartyRole.VENDOR.defaultGroupCode());
    }

    @Test
    @DisplayName("a party that is both may live in either group; the others only in their own")
    void acceptedGroups() {
        assertTrue(PartyRole.BOTH.acceptsGroup("SUNDRY_CREDITORS"));
        assertTrue(PartyRole.BOTH.acceptsGroup("SUNDRY_DEBTORS"));
        assertFalse(PartyRole.CUSTOMER.acceptsGroup("SUNDRY_CREDITORS"));
        assertFalse(PartyRole.VENDOR.acceptsGroup("SUNDRY_DEBTORS"));
        assertFalse(PartyRole.BOTH.acceptsGroup("CASH_AND_BANK"));
    }

    @Test
    @DisplayName("who we sell to and buy from")
    void sellsAndBuys() {
        assertTrue(PartyRole.CUSTOMER.sells());
        assertFalse(PartyRole.CUSTOMER.buys());
        assertTrue(PartyRole.VENDOR.buys());
        assertFalse(PartyRole.VENDOR.sells());
        assertTrue(PartyRole.BOTH.sells() && PartyRole.BOTH.buys());
    }

    @Test
    @DisplayName("a profile normalises blanks to null and tax identifiers to upper case")
    void profileNormalises() {
        PartyProfile profile = new PartyProfile(" ", null, "", null, null, null, "27", null,
                " 27abcde1234f1z5 ", "abcde1234f", null, null);
        assertNull(profile.code());
        assertNull(profile.email());
        assertEquals("27ABCDE1234F1Z5", profile.gstin());
        assertEquals("ABCDE1234F", profile.pan());
    }

    @Test
    @DisplayName("a negative credit limit or credit period is refused")
    void negativeCreditRefused() {
        assertThrows(PartyException.class, () -> new PartyProfile(null, null, null, null, null,
                null, null, null, null, null, new BigDecimal("-1"), null));
        assertThrows(PartyException.class, () -> new PartyProfile(null, null, null, null, null,
                null, null, null, null, null, null, -5));
    }
}
