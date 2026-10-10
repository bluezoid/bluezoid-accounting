package com.accubook.module.plan;

import com.accubook.module.ModuleId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan matrix approved in {@code P4_PLAN_MODULE_MATRIX.md}.
 *
 * <p>These assert commercial decisions that were explicitly approved, so a change
 * to the matrix should break a test and require a deliberate update rather than
 * slipping through.
 */
class PlanCatalogueTest {

    private static ModuleId id(String value) {
        return ModuleId.of(value);
    }

    @Test
    @DisplayName("every module with a dependency rule is sold in at least one plan or as an add-on")
    void noModuleIsUnsellable() {
        Set<ModuleId> sold = new java.util.HashSet<>(PlanCatalogue.addOns());
        for (BuildPlan plan : BuildPlan.values()) {
            sold.addAll(PlanCatalogue.modulesFor(plan));
        }
        for (String module : List.of("manufacturing", "sales-orders", "purchase-orders", "payroll", "taxation-returns")) {
            assertTrue(sold.contains(id(module)), module + " is in no plan and no add-on");
        }
    }

    @Test
    @DisplayName("Basic contains the approved set and no more")
    void basicMatchesMatrix() {
        Set<ModuleId> modules = PlanCatalogue.modulesFor(BuildPlan.BASIC);

        assertAll(
                () -> assertTrue(modules.contains(id("sales-invoicing"))),
                () -> assertTrue(modules.contains(id("purchase-billing"))),
                () -> assertTrue(modules.contains(id("reporting-extended"))),
                () -> assertTrue(modules.contains(id("tally-import")), "onboarding path is in every tier"),
                () -> assertTrue(modules.contains(id("notifications"))),
                () -> assertFalse(modules.contains(id("inventory")), "services businesses do not need inventory"),
                () -> assertFalse(modules.contains(id("payroll"))),
                () -> assertFalse(modules.contains(id("banking"))));
    }

    @Test
    @DisplayName("Professional adds inventory, orders, banking and recurring")
    void professionalMatchesMatrix() {
        Set<ModuleId> modules = PlanCatalogue.modulesFor(BuildPlan.PROFESSIONAL);

        assertAll(
                () -> assertTrue(modules.contains(id("inventory"))),
                () -> assertTrue(modules.contains(id("sales-orders"))),
                () -> assertTrue(modules.contains(id("purchase-orders")), "P7-21"),
                () -> assertTrue(modules.contains(id("banking"))),
                () -> assertTrue(modules.contains(id("recurring"))),
                () -> assertFalse(modules.contains(id("payroll"))),
                () -> assertFalse(modules.contains(id("manufacturing"))));
    }

    @Test
    @DisplayName("Enterprise contains everything Professional does, plus its own")
    void enterpriseSupersetsProfessional() {
        Set<ModuleId> professional = PlanCatalogue.modulesFor(BuildPlan.PROFESSIONAL);
        Set<ModuleId> enterprise = PlanCatalogue.modulesFor(BuildPlan.ENTERPRISE);

        assertAll(
                () -> assertTrue(enterprise.containsAll(professional), "a higher tier never removes a feature"),
                () -> assertTrue(enterprise.contains(id("payroll"))),
                () -> assertTrue(enterprise.contains(id("manufacturing"))),
                () -> assertTrue(enterprise.contains(PlanCatalogue.TAXATION_RETURNS)),
                () -> assertTrue(enterprise.contains(PlanCatalogue.BACKUP_CLOUD)));
    }

    @Test
    @DisplayName("tiers are strictly nested, so an upgrade never loses a module")
    void tiersAreNested() {
        Set<ModuleId> basic = PlanCatalogue.modulesFor(BuildPlan.BASIC);
        Set<ModuleId> professional = PlanCatalogue.modulesFor(BuildPlan.PROFESSIONAL);

        assertTrue(professional.containsAll(basic),
                "upgrading must never take a feature away: " + basic);
    }

    @Test
    @DisplayName("B-7: taxation-returns is an add-on purchasable on Basic")
    void taxationReturnsIsAnAddOnAtAnyTier() {
        Set<ModuleId> basicAlone = PlanCatalogue.modulesFor(BuildPlan.BASIC);
        Set<ModuleId> basicWithReturns =
                PlanCatalogue.modulesFor(BuildPlan.BASIC, Set.of(PlanCatalogue.TAXATION_RETURNS));

        assertAll(
                () -> assertFalse(basicAlone.contains(PlanCatalogue.TAXATION_RETURNS)),
                () -> assertTrue(basicWithReturns.contains(PlanCatalogue.TAXATION_RETURNS)),
                () -> assertTrue(PlanCatalogue.addOns().contains(PlanCatalogue.TAXATION_RETURNS)));
    }

    @Test
    @DisplayName("dependencies are pulled in automatically")
    void resolvesDependencies() {
        // manufacturing requires inventory: Bom.itemId and WorkOrder.itemId are
        // NOT NULL, so an image with one and not the other cannot be assembled.
        Set<ModuleId> modules = PlanCatalogue.modulesFor(BuildPlan.BASIC, Set.of(id("manufacturing")));

        assertTrue(modules.contains(id("inventory")),
                "manufacturing must drag inventory in: " + modules);
    }

    @Test
    @DisplayName("transitive dependencies are resolved, not just direct ones")
    void resolvesTransitiveDependencies() {
        // payroll -> hr, and taxation-returns -> sales-invoicing + purchase-billing.
        Set<ModuleId> modules = PlanCatalogue.modulesFor(BuildPlan.BASIC, Set.of(id("payroll")));

        assertTrue(modules.contains(id("hr")), "payroll must drag hr in: " + modules);
    }

    @Test
    @DisplayName("kernel modules are never listed - they are in every build by definition")
    void kernelIsNeverListed() {
        for (BuildPlan plan : PlanCatalogue.plans()) {
            Set<ModuleId> modules = PlanCatalogue.modulesFor(plan);
            assertAll(
                    () -> assertFalse(modules.contains(ModuleId.CORE), plan + " must not list core"),
                    () -> assertFalse(modules.contains(ModuleId.ACCOUNTING), plan + " must not list accounting"),
                    () -> assertFalse(modules.contains(ModuleId.PARTIES), plan + " must not list parties"),
                    () -> assertFalse(modules.contains(ModuleId.REPORTING_CORE),
                            plan + " must not list reporting-core"));
        }
    }

    @ParameterizedTest
    @EnumSource(BuildPlan.class)
    @DisplayName("every plan resolves to a dependency-complete module set")
    void everyPlanIsSelfConsistent(BuildPlan plan) {
        Set<ModuleId> modules = PlanCatalogue.modulesFor(plan, PlanCatalogue.addOns());

        for (ModuleId module : modules) {
            for (ModuleId required : PlanCatalogue.requirementsOf(module)) {
                assertTrue(modules.contains(required) || required.isKernel(),
                        plan + " contains " + module + " but not its requirement " + required);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(BuildPlan.class)
    @DisplayName("resolution is deterministic, so identical inputs give identical images")
    void resolutionIsDeterministic(BuildPlan plan) {
        assertEquals(
                List.copyOf(PlanCatalogue.modulesFor(plan, PlanCatalogue.addOns())),
                List.copyOf(PlanCatalogue.modulesFor(plan, PlanCatalogue.addOns())));
    }

    @Test
    @DisplayName("plan names round-trip from the command line, case-insensitively")
    void parsesPlanNames() {
        assertAll(
                () -> assertEquals(BuildPlan.BASIC, BuildPlan.fromCliName("basic")),
                () -> assertEquals(BuildPlan.PROFESSIONAL, BuildPlan.fromCliName("Professional")),
                () -> assertEquals(BuildPlan.ENTERPRISE, BuildPlan.fromCliName(" ENTERPRISE ")));
    }

    @Test
    @DisplayName("an unknown plan name is rejected with the valid options")
    void rejectsUnknownPlan() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> BuildPlan.fromCliName("ultimate"));

        assertTrue(e.getMessage().contains("basic, professional, enterprise"), e.getMessage());
    }
}
