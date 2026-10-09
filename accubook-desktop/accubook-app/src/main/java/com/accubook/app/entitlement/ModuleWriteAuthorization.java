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
 * <h2>Unowned tables</h2>
 * A table no module claims is a <em>kernel</em> table - companies, migrations,
 * settings, the chart of accounts. These are permitted, because they are not
 * part of any sellable module and blocking them would stop the application
 * opening at all.
 *
 * <p>This is the one place the guard fails open, so it is bounded deliberately:
 * ownership is validated at startup by {@link ModuleRegistry}, which refuses to
 * start when two modules claim the same table. A feature table that reached
 * production unowned would be permitted - which is why table ownership is
 * asserted in the architecture tests rather than left to review.
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
        Optional<ModuleId> owner = registry.ownerOf(table);
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
