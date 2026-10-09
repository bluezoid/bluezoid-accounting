package com.accubook.module;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * What this installation physically contains.
 *
 * <p>Answers "what is <em>installed</em>" and nothing else. It does not decide
 * whether anything may be used - that is the entitlement's job, and keeping the
 * two apart is what stops the registry becoming an authorization source.
 *
 * <h2>Discovery</h2>
 * {@link ServiceLoader} over {@link FeatureModule}. Measured behaviour: a module
 * absent from the build yields an empty result rather than an exception, both on
 * a plain module path and inside a jlink image. That is precisely the downgrade
 * behaviour required - a Basic build simply finds fewer providers, with no
 * {@code if (plan == ...)} anywhere.
 *
 * <h2>Validation</h2>
 * The graph is checked once, at construction. A malformed optional module must
 * not be able to damage the application, so problems are reported as a single
 * {@link ModuleRegistryException} naming everything wrong rather than failing on
 * the first fault and hiding the rest.
 */
public final class ModuleRegistry {

    private final Map<ModuleId, FeatureModule> byId;
    private final List<FeatureModule> inNavigationOrder;

    /**
     * Table name to owning module, keyed in lower case.
     *
     * <p>Computed once rather than scanned per write. The persistence guard
     * consults this on every mutating statement, and it derives the table from
     * SQL where case is not significant - so a module declaring
     * {@code "Invoices"} must still match a statement writing {@code invoices}.
     * Normalising here rather than at each call site is what stops a
     * capitalisation difference silently becoming an unguarded table.
     */
    private final Map<String, ModuleId> ownership;

    private ModuleRegistry(Map<ModuleId, FeatureModule> byId) {
        this.byId = Map.copyOf(byId);
        List<FeatureModule> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator
                .comparingInt(FeatureModule::navigationOrder)
                .thenComparing(m -> m.id().value()));
        this.inNavigationOrder = List.copyOf(ordered);

        Map<String, ModuleId> owners = new LinkedHashMap<>();
        for (FeatureModule module : inNavigationOrder) {
            for (String table : module.ownedTables()) {
                owners.put(normalise(table), module.id());
            }
        }
        this.ownership = Map.copyOf(owners);
    }

    private static String normalise(String table) {
        return table.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** Discover the modules linked into this build. */
    public static ModuleRegistry discover() {
        return discover(ServiceLoader.load(FeatureModule.class));
    }

    /** Discover from a supplied source, so tests need not manipulate the module path. */
    public static ModuleRegistry discover(Iterable<FeatureModule> providers) {
        Map<ModuleId, FeatureModule> found = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (FeatureModule module : providers) {
            ModuleId id;
            try {
                id = module.id();
            } catch (RuntimeException e) {
                // A module whose metadata throws is broken, but it must not take
                // the application down with it.
                problems.add("A module failed to report its id: " + e.getMessage());
                continue;
            }
            if (id == null) {
                problems.add("A module reported a null id (" + module.getClass().getName() + ")");
                continue;
            }
            FeatureModule clash = found.putIfAbsent(id, module);
            if (clash != null) {
                problems.add("Duplicate module id '" + id + "' provided by "
                        + clash.getClass().getName() + " and " + module.getClass().getName());
            }
        }

        problems.addAll(validateMetadata(found));
        problems.addAll(validateTableOwnership(found));
        problems.addAll(validateDependencies(found));
        problems.addAll(detectCycles(found));

        if (!problems.isEmpty()) {
            throw new ModuleRegistryException(
                    "This installation's modules are not valid:\n  - " + String.join("\n  - ", problems));
        }
        return new ModuleRegistry(found);
    }

    private static List<String> validateMetadata(Map<ModuleId, FeatureModule> modules) {
        List<String> problems = new ArrayList<>();
        modules.forEach((id, module) -> {
            try {
                if (module.displayName() == null || module.displayName().isBlank()) {
                    problems.add("Module '" + id + "' has no display name");
                }
                if (module.version() == null || module.version().isBlank()) {
                    problems.add("Module '" + id + "' has no version");
                }
                if (module.dependsOn() == null) {
                    problems.add("Module '" + id + "' returned null dependencies");
                }
                if (module.ownedTables() == null) {
                    problems.add("Module '" + id + "' returned null owned tables");
                }
            } catch (RuntimeException e) {
                problems.add("Module '" + id + "' failed while reporting metadata: " + e.getMessage());
            }
        });
        return problems;
    }

    /**
     * No table may have two owners.
     *
     * <p>The write guard resolves a table to exactly one module to decide whether
     * a write is permitted. Two claimants would make that answer depend on
     * iteration order, so the ambiguity is refused at startup instead of
     * producing an enforcement decision nobody can predict.
     */
    private static List<String> validateTableOwnership(Map<ModuleId, FeatureModule> modules) {
        List<String> problems = new ArrayList<>();
        Map<String, ModuleId> seen = new LinkedHashMap<>();
        modules.forEach((id, module) -> {
            Set<String> tables;
            try {
                tables = module.ownedTables();
            } catch (RuntimeException e) {
                return; // already reported by validateMetadata
            }
            if (tables == null) {
                return;
            }
            for (String table : tables) {
                if (table == null || table.isBlank()) {
                    problems.add("Module '" + id + "' declares a blank owned table");
                    continue;
                }
                ModuleId previous = seen.putIfAbsent(normalise(table), id);
                if (previous != null && !previous.equals(id)) {
                    problems.add("Table '" + normalise(table) + "' is claimed by both '"
                            + previous + "' and '" + id + "'");
                }
            }
        });
        return problems;
    }

    private static List<String> validateDependencies(Map<ModuleId, FeatureModule> modules) {
        List<String> problems = new ArrayList<>();
        modules.forEach((id, module) -> {
            Set<ModuleId> declared = safeDependencies(module);
            for (ModuleId dependency : declared) {
                // A kernel dependency is always satisfied: kernel modules are in
                // every build by definition and are not ServiceLoader providers.
                if (dependency.isKernel()) {
                    continue;
                }
                if (!modules.containsKey(dependency)) {
                    problems.add("Module '" + id + "' requires '" + dependency
                            + "', which is not present in this build");
                }
                if (dependency.equals(id)) {
                    problems.add("Module '" + id + "' depends on itself");
                }
            }
        });
        return problems;
    }

    /** Depth-first cycle detection, reporting the offending path. */
    private static List<String> detectCycles(Map<ModuleId, FeatureModule> modules) {
        List<String> problems = new ArrayList<>();
        Set<ModuleId> settled = new HashSet<>();

        for (ModuleId start : modules.keySet()) {
            if (settled.contains(start)) {
                continue;
            }
            Set<ModuleId> onPath = new LinkedHashSet<>();
            walk(start, modules, onPath, settled, problems);
        }
        return problems;
    }

    private static void walk(ModuleId current, Map<ModuleId, FeatureModule> modules,
                             Set<ModuleId> onPath, Set<ModuleId> settled, List<String> problems) {
        if (settled.contains(current)) {
            return;
        }
        if (!onPath.add(current)) {
            List<String> path = onPath.stream().map(ModuleId::value).toList();
            problems.add("Dependency cycle: " + String.join(" -> ", path) + " -> " + current);
            return;
        }
        FeatureModule module = modules.get(current);
        if (module != null) {
            for (ModuleId dependency : safeDependencies(module)) {
                if (modules.containsKey(dependency)) {
                    walk(dependency, modules, onPath, settled, problems);
                }
            }
        }
        onPath.remove(current);
        settled.add(current);
    }

    private static Set<ModuleId> safeDependencies(FeatureModule module) {
        try {
            Set<ModuleId> declared = module.dependsOn();
            return declared == null ? Set.of() : declared;
        } catch (RuntimeException e) {
            return Set.of();
        }
    }

    /** True when {@code id} is packaged in this build. */
    public boolean isInstalled(ModuleId id) {
        return byId.containsKey(id) || id.isKernel();
    }

    /** Ids physically present, excluding the always-present kernel. */
    public Set<ModuleId> installedModuleIds() {
        return new LinkedHashSet<>(byId.keySet());
    }

    public Optional<FeatureModule> find(ModuleId id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** Modules in the order the shell should display them. */
    public List<FeatureModule> modules() {
        return inNavigationOrder;
    }

    /**
     * Which module owns {@code table}, if any.
     *
     * <p>The persistence guard's lookup. Tables no module claims belong to the
     * kernel and are governed by the subscription rather than by a module.
     */
    public Optional<ModuleId> ownerOf(String table) {
        if (table == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ownership.get(normalise(table)));
    }

    /** Every historical view declared by installed modules, for the generic viewer. */
    public Map<ModuleId, List<HistoricalView>> historicalViews() {
        Map<ModuleId, List<HistoricalView>> views = new LinkedHashMap<>();
        for (FeatureModule module : inNavigationOrder) {
            List<HistoricalView> declared = module.historicalViews();
            if (declared != null && !declared.isEmpty()) {
                views.put(module.id(), List.copyOf(declared));
            }
        }
        return views;
    }

    /** Table-to-owner map, for diagnostics. */
    public Map<String, ModuleId> tableOwnership() {
        return ownership;
    }
}
