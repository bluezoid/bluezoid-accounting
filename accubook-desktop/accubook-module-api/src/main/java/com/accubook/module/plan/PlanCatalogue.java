package com.accubook.module.plan;

import com.accubook.module.ModuleId;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which modules each plan contains, and what each module requires.
 *
 * <p>Encodes the matrix approved in {@code docs/decisions/P4_PLAN_MODULE_MATRIX.md}.
 * It is consulted by the <em>build</em>, never by the running application: a plan
 * name must not be able to authorise anything at runtime, or it becomes a second
 * source of truth competing with the entitlement.
 *
 * <h2>Dependencies are technical, not commercial</h2>
 * The edges below are facts about the reference schema - {@code Bom.itemId} and
 * {@code WorkOrder.itemId} are NOT NULL, so manufacturing cannot be packaged
 * without inventory. A plan that violates one produces an image that cannot be
 * assembled, so the closure is computed rather than trusted to a hand-maintained
 * list.
 */
public final class PlanCatalogue {

    /** Add-ons are sold separately and may accompany any plan. */
    public static final ModuleId TAXATION_RETURNS = ModuleId.of("taxation-returns");
    public static final ModuleId BACKUP_CLOUD = ModuleId.of("backup-cloud");

    private static final Map<ModuleId, Set<ModuleId>> REQUIREMENTS = Map.of(
            ModuleId.of("manufacturing"), Set.of(ModuleId.of("inventory")),
            ModuleId.of("sales-orders"), Set.of(ModuleId.of("inventory")),
            ModuleId.of("purchase-orders"), Set.of(ModuleId.of("inventory")),
            ModuleId.of("payroll"), Set.of(ModuleId.of("hr")),
            TAXATION_RETURNS, Set.of(ModuleId.of("sales-invoicing"), ModuleId.of("purchase-billing")));

    /** Present in every plan, on top of the kernel. */
    private static final Set<ModuleId> IN_EVERY_PLAN = ids(
            "sales-invoicing", "purchase-billing", "reporting-extended", "tally-import", "notifications");

    private static final Map<BuildPlan, Set<ModuleId>> DECLARED = new LinkedHashMap<>();

    static {
        DECLARED.put(BuildPlan.BASIC, union(IN_EVERY_PLAN));
        DECLARED.put(BuildPlan.PROFESSIONAL, union(IN_EVERY_PLAN,
                // purchase-orders added by P7-21: it had a dependency rule but
                // no plan, so no customer could ever have it.
                ids("inventory", "sales-orders", "purchase-orders", "banking", "recurring")));
        DECLARED.put(BuildPlan.ENTERPRISE, union(DECLARED.get(BuildPlan.PROFESSIONAL),
                ids("hr", "payroll", "manufacturing", "budgets", "approvals", "ocr"),
                Set.of(TAXATION_RETURNS, BACKUP_CLOUD)));
    }

    private PlanCatalogue() {
    }

    /**
     * The modules a plan's image must contain, dependencies included.
     *
     * @param plan    the plan being built
     * @param addOns  separately purchased modules to include
     */
    public static Set<ModuleId> modulesFor(BuildPlan plan, Set<ModuleId> addOns) {
        Set<ModuleId> requested = new LinkedHashSet<>(DECLARED.get(plan));
        if (addOns != null) {
            requested.addAll(addOns);
        }
        return close(requested);
    }

    public static Set<ModuleId> modulesFor(BuildPlan plan) {
        return modulesFor(plan, Set.of());
    }

    /** Modules sold separately from any tier. */
    public static Set<ModuleId> addOns() {
        return Set.of(TAXATION_RETURNS, BACKUP_CLOUD);
    }

    /** What {@code module} directly requires. */
    public static Set<ModuleId> requirementsOf(ModuleId module) {
        return REQUIREMENTS.getOrDefault(module, Set.of());
    }

    /**
     * Add every transitive requirement.
     *
     * <p>Computed rather than declared: a plan listing {@code manufacturing}
     * without {@code inventory} would otherwise produce an image whose foreign
     * keys cannot resolve. Adding the dependency is the right answer because the
     * commercial intent - "this plan includes manufacturing" - is only meaningful
     * if manufacturing works.
     */
    private static Set<ModuleId> close(Set<ModuleId> requested) {
        Set<ModuleId> resolved = new LinkedHashSet<>();
        Deque<ModuleId> pending = new ArrayDeque<>(requested);
        while (!pending.isEmpty()) {
            ModuleId current = pending.removeFirst();
            if (current.isKernel() || !resolved.add(current)) {
                continue;
            }
            pending.addAll(requirementsOf(current));
        }
        // Sorted so a build is reproducible: the module list feeds jlink, and an
        // unstable order would make otherwise identical images differ.
        return new LinkedHashSet<>(new TreeSet<>(resolved));
    }

    @SafeVarargs
    private static Set<ModuleId> union(Set<ModuleId>... sets) {
        Set<ModuleId> all = new LinkedHashSet<>();
        for (Set<ModuleId> set : sets) {
            all.addAll(set);
        }
        return all;
    }

    private static Set<ModuleId> ids(String... values) {
        Set<ModuleId> all = new LinkedHashSet<>();
        for (String value : values) {
            all.add(ModuleId.of(value));
        }
        return all;
    }

    /** Every module any plan can contain, for build validation. */
    public static Set<ModuleId> allKnownModules() {
        Set<ModuleId> all = new LinkedHashSet<>();
        DECLARED.values().forEach(all::addAll);
        all.addAll(addOns());
        REQUIREMENTS.forEach((module, requires) -> {
            all.add(module);
            all.addAll(requires);
        });
        return new LinkedHashSet<>(new TreeSet<>(all));
    }

    /** Plans in ascending order, for documentation and the build script. */
    public static List<BuildPlan> plans() {
        return List.of(BuildPlan.BASIC, BuildPlan.PROFESSIONAL, BuildPlan.ENTERPRISE);
    }
}
