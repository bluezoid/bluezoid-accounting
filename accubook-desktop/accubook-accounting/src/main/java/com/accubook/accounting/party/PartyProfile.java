package com.accubook.accounting.party;

import java.math.BigDecimal;

/**
 * A party's descriptive details - everything except identity, role and ledger.
 *
 * <p>GSTIN, PAN and state code are stored now and validated in P9, where they
 * start to drive tax. Blank strings are normalised to {@code null} so "not
 * given" has one representation.
 */
public record PartyProfile(
        String code,
        String contactPerson,
        String email,
        String phone,
        String addressLine1,
        String city,
        String stateCode,
        String postalCode,
        String gstin,
        String pan,
        BigDecimal creditLimit,
        Integer creditDays) {

    public PartyProfile {
        code = clean(code);
        contactPerson = clean(contactPerson);
        email = clean(email);
        phone = clean(phone);
        addressLine1 = clean(addressLine1);
        city = clean(city);
        stateCode = clean(stateCode);
        postalCode = clean(postalCode);
        gstin = upper(gstin);
        pan = upper(pan);
        if (creditLimit != null && creditLimit.signum() < 0) {
            throw new PartyException("A credit limit cannot be negative");
        }
        if (creditDays != null && creditDays < 0) {
            throw new PartyException("Credit days cannot be negative");
        }
    }

    /** No details at all. */
    public static PartyProfile empty() {
        return new PartyProfile(null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String upper(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toUpperCase(java.util.Locale.ROOT);
    }
}
