package com.accubook.module;

import java.util.List;
import java.util.Set;

/**
 * A physically packaged feature module.
 *
 * <p>Discovered at runtime through {@link java.util.ServiceLoader}, so the shell
 * renders navigation from whatever modules the build actually contains without
 * importing any of them. Excluding a module from a build removes its code, its
 * UI and its use cases - a physical exclusion, not a hidden button.
 *
 * <h2>What is deliberately absent</h2>
 * Nothing licensing-shaped. A module never learns that licences, signatures,
 * machine fingerprints, subscription dates or activation servers exist; it asks
 * {@link ModuleStateProvider#moduleState} and acts on the answer.
 *
 * <p>That is not tidiness. The reference application checked permissions in each
 * handler and <strong>55 of 71 mutating routes forgot</strong> - a VIEWER could
 * post journal vouchers and run payroll. One narrow contract, with enforcement
 * behind it, is what stops that drift recurring here.
 *
 * <h2>Implementations must be trivial to construct</h2>
 * {@code ServiceLoader} instantiates providers with a no-argument constructor
 * during discovery, before the application has decided anything. An implementation
 * that opened a database or read a file here would run that work in every build,
 * including one where the module is not entitled.
 */
public interface FeatureModule {

    /** Stable identity, used in builds, entitlements and the registry. */
    ModuleId id();

    /** Human-readable name for navigation. Never used for identity. */
    String displayName();

    /**
     * Modules this one cannot function without.
     *
     * <p>Validated at build time so an impossible image is rejected before it is
     * compiled rather than failing at a customer's first launch. Derived from
     * real coupling: manufacturing cannot work without inventory because
     * {@code Bom.itemId} and {@code WorkOrder.itemId} are non-nullable.
     */
    default Set<ModuleId> dependsOn() {
        return Set.of();
    }

    /** Module version, for the diagnostic manifest and compatibility reporting. */
    default String version() {
        return "0.1.0";
    }

    /** Order hint for navigation; lower sorts first. */
    default int navigationOrder() {
        return 100;
    }

    /**
     * Tables this module owns.
     *
     * <p>Read by the persistence guard, which refuses writes to these tables when
     * the module is not {@link ModuleState#ACTIVE}. Declared here so ownership
     * travels with the module rather than living in a central list that drifts
     * out of date.
     *
     * <p><strong>Ownership rule:</strong> a module owns its engines and its own
     * tables. It never owns columns on another module's tables, and it never owns
     * posted general-ledger entries. A payroll journal entry is a <em>ledger</em>
     * entry: it must stay in the trial balance after a payroll downgrade, or the
     * books stop balancing.
     */
    default Set<String> ownedTables() {
        return Set.of();
    }

    /**
     * The lowest company-schema version this module can operate against.
     *
     * <p>Lets a module decline rather than misread an older database. Zero means
     * "any", which suits modules that own no tables.
     */
    default int requiredSchemaVersion() {
        return 0;
    }

    /**
     * Declarative descriptions of this module's historical records, for the
     * generic viewer.
     *
     * <p>Metadata rather than code, so the base build can show a downgraded
     * customer their payroll history without containing the payroll engine. The
     * viewer executes no module logic and can only read.
     */
    default List<HistoricalView> historicalViews() {
        return List.of();
    }
}
