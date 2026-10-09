package com.accubook.module;

import java.util.Locale;
import java.util.Set;

/**
 * A module's stable identity.
 *
 * <p>A value type rather than a bare {@code String} because this id appears in
 * three places that must agree exactly: the build's module selection, the signed
 * entitlement issued by the licence server, and the runtime registry. A typo in
 * any one of them would silently disable a paid feature, and a raw string offers
 * no help in catching that.
 *
 * <p>Ids are lower-case kebab-case, validated on construction, and never shown
 * to customers - {@link FeatureModule#displayName()} is for that.
 */
public record ModuleId(String value) implements Comparable<ModuleId> {

    /**
     * Modules that are part of every build and can never be withheld.
     *
     * <p>Derived from the reference application's actual coupling, not from
     * preference: {@code Organization} carries 40 back-relations,
     * {@code Voucher.fiscalYearId}/{@code createdById}/{@code voucherTypeId} are
     * non-nullable, the posting layer resolves ledger groups by name and throws
     * when they are missing, and {@code Ledger.partyId} makes party ledgers part
     * of the chart of accounts itself.
     */
    // Declared before the constants below: static initialisers run in source
    // order, and the constants invoke the constructor, which reads this pattern.
    // Declaring it afterwards leaves it null during class initialisation and
    // fails with ExceptionInInitializerError.
    private static final java.util.regex.Pattern VALID =
            java.util.regex.Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public static final ModuleId CORE = new ModuleId("core");
    public static final ModuleId ACCOUNTING = new ModuleId("accounting");
    public static final ModuleId PARTIES = new ModuleId("parties");
    public static final ModuleId REPORTING_CORE = new ModuleId("reporting-core");

    private static final Set<ModuleId> KERNEL =
            Set.of(CORE, ACCOUNTING, PARTIES, REPORTING_CORE);

    public ModuleId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Module id must not be blank");
        }
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Module id must be lower-case kebab-case, was '" + value + "'");
        }
    }

    /** Parse user or build input, normalising case and surrounding whitespace. */
    public static ModuleId of(String raw) {
        return new ModuleId(raw == null ? null : raw.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * True when this module is part of every build.
     *
     * <p>The build system refuses to produce an image missing any of these, and
     * entitlement resolution treats them as always entitled - a customer whose
     * licence somehow omitted {@code accounting} would otherwise be unable to
     * open their own books.
     */
    public boolean isKernel() {
        return KERNEL.contains(this);
    }

    /** The modules present in every build. */
    public static Set<ModuleId> kernel() {
        return KERNEL;
    }

    @Override
    public int compareTo(ModuleId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
