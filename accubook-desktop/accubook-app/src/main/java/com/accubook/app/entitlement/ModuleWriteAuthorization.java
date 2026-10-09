package com.accubook.app.entitlement;

import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleStateProvider;
import com.accubook.persistence.guard.WriteAuthorization;

import java.util.Objects;
import java.util.Optional;

/**
 * Answers the persistence guard's question - "may I write this table?" - from
 * module ownership and the current entitlement.
 *
 * <p>This class is the only place the two sides meet. Persistence knows about
 * tables and nothing else; the module layer knows about modules and nothing
 * about SQL. Keeping the join here is what lets either side be tested without
 * the other, and stops the persistence layer growing a licensing dependency.
 *
 * <h2>Who owns a table</h2>
 * {@link TableOwners} first: it classifies every table the shipped schema
 * creates, so a feature table keeps its owner in a build that does not contain
 * the module - a BASIC build's inventory tables are owned by an inventory
 * module that is DORMANT there, and are refused. Kernel tables are never gated,
 * even if a module wrongly claims one.
 *
 * <p>A table neither the catalogue nor any installed module knows is permitted
 * as kernel. That is the one remaining way the guard fails open, and it is
 * bounded by a test asserting that every table the migrations create is
 * classified in {@link TableOwners}.
 */
public final class ModuleWriteAuthorization implements WriteAuthorization {

    private final ModuleRegistry registry;
    private final ModuleStateProvider states;

    public ModuleWriteAuthorization(ModuleRegistry registry, ModuleStateProvider states) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.states = Objects.requireNonNull(states, "states");
    }

    @Override
    public Optional<String> refuse(String table) {
        // Kernel tables are never gated - even if a module wrongly claims one,
        // which would otherwise let a downgrade stop the books balancing.
        if (TableOwners.isKernel(table)) {
            return Optional.empty();
        }
        // The schema's catalogue first: it knows a feature table's owner even
        // when that module is not in this build. The registry covers tables
        // the catalogue does not know, such as a test's own.
        Optional<ModuleId> owner = TableOwners.featureOwnerOf(table).or(() -> registry.ownerOf(table));
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        ModuleId moduleId = owner.get();
        ModuleState state = states.moduleState(moduleId.value());
        if (state.allowsMutation()) {
            return Optional.empty();
        }
        return Optional.of("the '" + moduleId + "' module is " + state
                + ". Existing records remain readable and exportable.");
    }
}
