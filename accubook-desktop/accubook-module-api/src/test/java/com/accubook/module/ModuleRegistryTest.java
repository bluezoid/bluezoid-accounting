package com.accubook.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.accubook.module.TestModule.module;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry decides what this installation physically contains, and a wrong
 * answer is not a cosmetic fault: the write guard resolves tables through it, so
 * a table that resolves to no owner is a table nothing protects.
 */
class ModuleRegistryTest {

    @Nested
    @DisplayName("discovery")
    class Discovery {

        @Test
        @DisplayName("an empty build is valid, not an error - that is what a Basic build looks like")
        void emptyBuildIsValid() {
            ModuleRegistry registry = ModuleRegistry.discover(List.of());

            assertAll(
                    () -> assertTrue(registry.modules().isEmpty()),
                    () -> assertTrue(registry.installedModuleIds().isEmpty()),
                    () -> assertTrue(registry.ownerOf("payroll_runs").isEmpty()));
        }

        @Test
        @DisplayName("kernel modules count as installed even though they are not providers")
        void kernelIsAlwaysInstalled() {
            ModuleRegistry registry = ModuleRegistry.discover(List.of());

            assertAll(
                    () -> assertTrue(registry.isInstalled(ModuleId.CORE)),
                    () -> assertTrue(registry.isInstalled(ModuleId.ACCOUNTING)),
                    () -> assertFalse(registry.isInstalled(ModuleId.of("payroll"))));
        }

        @Test
        @DisplayName("modules are ordered by navigation order, then by id for stability")
        void ordersModules() {
            ModuleRegistry registry = ModuleRegistry.discover(List.of(
                    module("payroll").navigationOrder(30),
                    module("inventory").navigationOrder(10),
                    module("banking").navigationOrder(10)));

            assertEquals(List.of("banking", "inventory", "payroll"),
                    registry.modules().stream().map(m -> m.id().value()).toList());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("two modules claiming one id is refused")
        void duplicateIdRefused() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(module("payroll"), module("payroll"))));

            assertTrue(e.getMessage().contains("Duplicate module id 'payroll'"), e.getMessage());
        }

        @Test
        @DisplayName("a missing dependency is refused rather than failing at first use")
        void missingDependencyRefused() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(
                            module("manufacturing").dependsOn("inventory"))));

            assertTrue(e.getMessage().contains("requires 'inventory'"), e.getMessage());
        }

        @Test
        @DisplayName("a kernel dependency is satisfied without a provider")
        void kernelDependencyIsSatisfied() {
            ModuleRegistry registry = ModuleRegistry.discover(List.of(
                    module("payroll").dependsOn("accounting", "core")));

            assertTrue(registry.isInstalled(ModuleId.of("payroll")));
        }

        @Test
        @DisplayName("a dependency cycle is detected and named")
        void cycleDetected() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(
                            module("a").dependsOn("b"),
                            module("b").dependsOn("c"),
                            module("c").dependsOn("a"))));

            assertTrue(e.getMessage().contains("Dependency cycle"), e.getMessage());
        }

        @Test
        @DisplayName("a module depending on itself is refused")
        void selfDependencyRefused() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(module("a").dependsOn("a"))));

            assertTrue(e.getMessage().contains("depends on itself"), e.getMessage());
        }

        @Test
        @DisplayName("every problem is reported at once, not just the first")
        void reportsAllProblems() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(
                            module("a").dependsOn("missing-one"),
                            module("b").dependsOn("missing-two"),
                            module("c").displayName(" "))));

            String message = e.getMessage();
            assertAll(
                    () -> assertTrue(message.contains("missing-one"), message),
                    () -> assertTrue(message.contains("missing-two"), message),
                    () -> assertTrue(message.contains("has no display name"), message));
        }

        @Test
        @DisplayName("a module whose metadata throws is reported, not propagated")
        void brokenMetadataIsContained() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(
                            module("broken").failingMetadata(new IllegalStateException("disk gone")))));

            assertTrue(e.getMessage().contains("disk gone"), e.getMessage());
        }

        @Test
        @DisplayName("a module whose id throws is skipped rather than taking the build down")
        void brokenIdIsContained() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(
                            module("broken").failingId(new IllegalStateException("no id")))));

            assertTrue(e.getMessage().contains("failed to report its id"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("table ownership - consulted by the write guard on every mutation")
    class TableOwnership {

        @Test
        @DisplayName("resolves a table to its owning module")
        void resolvesOwner() {
            ModuleRegistry registry = ModuleRegistry.discover(List.of(
                    module("payroll").owning("payroll_runs", "payslips"),
                    module("inventory").owning("stock_movements")));

            assertAll(
                    () -> assertEquals(ModuleId.of("payroll"), registry.ownerOf("payroll_runs").orElseThrow()),
                    () -> assertEquals(ModuleId.of("inventory"), registry.ownerOf("stock_movements").orElseThrow()),
                    () -> assertTrue(registry.ownerOf("companies").isEmpty(), "kernel tables have no module owner"));
        }

        @Test
        @DisplayName("ownership is case-insensitive, because SQL table names are")
        void ownershipIsCaseInsensitive() {
            ModuleRegistry registry = ModuleRegistry.discover(List.of(
                    module("payroll").owning("Payroll_Runs")));

            // The guard derives the table from SQL text and lower-cases it. If
            // lookup were case-sensitive this table would resolve to no owner and
            // would therefore be writable while payroll was READ_ONLY.
            assertAll(
                    () -> assertTrue(registry.ownerOf("payroll_runs").isPresent()),
                    () -> assertTrue(registry.ownerOf("PAYROLL_RUNS").isPresent()),
                    () -> assertTrue(registry.ownerOf("Payroll_Runs").isPresent()));
        }

        @Test
        @DisplayName("two modules claiming one table is refused - the guard needs one answer")
        void duplicateTableOwnershipRefused() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(
                            module("payroll").owning("employees"),
                            module("hr").owning("employees"))));

            assertTrue(e.getMessage().contains("is claimed by both"), e.getMessage());
        }

        @Test
        @DisplayName("duplicate ownership is caught across differing case too")
        void duplicateTableOwnershipAcrossCase() {
            ModuleRegistryException e = assertThrows(ModuleRegistryException.class,
                    () -> ModuleRegistry.discover(List.of(
                            module("payroll").owning("employees"),
                            module("hr").owning("Employees"))));

            assertTrue(e.getMessage().contains("is claimed by both"), e.getMessage());
        }

        @Test
        @DisplayName("a null table lookup is empty, not an exception")
        void nullTableIsEmpty() {
            ModuleRegistry registry = ModuleRegistry.discover(List.of(module("payroll").owning("payroll_runs")));

            assertTrue(registry.ownerOf(null).isEmpty());
        }
    }
}
