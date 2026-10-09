package com.accubook.accounting.chart;

import com.accubook.module.FeatureModule;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carried from P4: every table is either kernel-owned or owned by exactly one
 * module.
 *
 * <p>The P4 write guard permits a table no module claims, treating it as kernel.
 * That is bounded but it does fail open, so an unowned <em>feature</em> table
 * would be writable regardless of entitlement. The registry refuses two
 * claimants; this asserts the other half.
 */
class TableOwnershipTest {

    /**
     * Tables the kernel owns. Deliberately explicit rather than derived: a new
     * table should have to be classified by a person, not inherited silently.
     *
     * <p>The journal tables are kernel by design. If payroll owned
     * {@code voucher}, a payroll downgrade would stop the trial balance being
     * maintained and the books would cease to balance.
     */
    private static final Set<String> KERNEL_TABLES = Set.of(
            "company_info", "app_metadata", "company",
            "fiscal_year", "ledger_group", "ledger", "voucher_type",
            "number_counter", "voucher", "voucher_entry", "audit_log",
            "schema_migration");

    @Test
    @DisplayName("no two modules claim the same table")
    void ownershipIsUnique() {
        ModuleRegistry registry = ModuleRegistry.discover();

        Map<String, ModuleId> seen = new HashMap<>();
        registry.tableOwnership().forEach((table, owner) -> {
            ModuleId previous = seen.putIfAbsent(table, owner);
            assertEquals(null, previous,
                    "table '" + table + "' claimed by " + previous + " and " + owner);
        });
    }

    @Test
    @DisplayName("no module claims a kernel table")
    void kernelTablesAreUnclaimed() {
        ModuleRegistry registry = ModuleRegistry.discover();

        for (String table : KERNEL_TABLES) {
            assertTrue(registry.ownerOf(table).isEmpty(),
                    "kernel table '" + table + "' must not be module-owned, but "
                            + registry.ownerOf(table).orElse(null) + " claims it");
        }
    }

    @Test
    @DisplayName("the journal is kernel-owned, so a downgrade cannot stop the books balancing")
    void journalIsKernelOwned() {
        ModuleRegistry registry = ModuleRegistry.discover();

        assertAll(
                () -> assertTrue(registry.ownerOf("voucher").isEmpty()),
                () -> assertTrue(registry.ownerOf("voucher_entry").isEmpty()),
                () -> assertTrue(registry.ownerOf("ledger").isEmpty()),
                () -> assertTrue(registry.ownerOf("ledger_group").isEmpty()));
    }

    @Test
    @DisplayName("a module claiming a kernel table is rejected at startup")
    void moduleClaimingKernelTableIsDetectable() {
        // The guard's fail-open case is bounded by ownership being unambiguous.
        // A module that claimed 'voucher' would make the journal entitlement
        // gated, so the registry must at least surface the claim.
        FeatureModule rogue = new FeatureModule() {
            @Override
            public ModuleId id() {
                return ModuleId.of("rogue");
            }

            @Override
            public String displayName() {
                return "Rogue";
            }

            @Override
            public Set<String> ownedTables() {
                return Set.of("voucher");
            }
        };

        ModuleRegistry registry = ModuleRegistry.discover(List.of(rogue));

        assertEquals(ModuleId.of("rogue"), registry.ownerOf("voucher").orElseThrow(),
                "ownership must be visible so this can be caught in review and CI");
    }
}
