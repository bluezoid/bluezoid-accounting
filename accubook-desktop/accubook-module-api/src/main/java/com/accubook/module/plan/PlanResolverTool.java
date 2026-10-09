package com.accubook.module.plan;

import com.accubook.module.ModuleId;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Command-line entry point letting the build script resolve a plan.
 *
 * <p>Exists so the plan matrix has exactly one definition. Encoding it a second
 * time in {@code build.sh} would guarantee eventual drift between what the build
 * packages and what the application believes a plan contains - and that drift is
 * invisible until a customer's image is missing a module.
 *
 * <pre>
 *   java -m accubook.module.api/com.accubook.module.plan.PlanResolverTool \
 *        professional taxation-returns
 * </pre>
 *
 * <p>Prints one module id per line on stdout; diagnostics go to stderr so the
 * caller can consume stdout directly.
 */
public final class PlanResolverTool {

    private PlanResolverTool() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: PlanResolverTool <plan> [add-on...]");
            System.err.println("Plans: " + PlanCatalogue.plans().stream()
                    .map(BuildPlan::cliName).collect(Collectors.joining(", ")));
            System.exit(2);
            return;
        }

        BuildPlan plan;
        try {
            plan = BuildPlan.fromCliName(args[0]);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        Set<ModuleId> addOns = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            try {
                addOns.add(ModuleId.of(args[i]));
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid add-on '" + args[i] + "': " + e.getMessage());
                System.exit(2);
                return;
            }
        }

        for (ModuleId module : PlanCatalogue.modulesFor(plan, addOns)) {
            System.out.println(module.value());
        }
    }
}
