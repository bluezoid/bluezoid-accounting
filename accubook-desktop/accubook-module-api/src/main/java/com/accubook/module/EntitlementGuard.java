package com.accubook.module;

import java.util.Objects;

/**
 * The single point where a mutation is authorised.
 *
 * <p>Every write passes through {@link #requireWritable}, whatever called it.
 * That breadth is the point: the reference application has at least three write
 * paths with no user present at all - a scheduler creating invoices
 * ({@code run-recurring.ts:200}), an OCR importer creating bills
 * ({@code post-extracted.ts:270}) and a Tally importer creating vouchers
 * ({@code tally.ts:654}). A guard attached to the user interface would miss all
 * three.
 *
 * <h2>Reads are never gated</h2>
 * There is deliberately no {@code requireReadable}. A customer's books are theirs
 * whatever their subscription says, so reading and exporting stay available in
 * every state. Withholding a customer's own history is both wrong and the main
 * reason people crack software rather than renew it.
 *
 * <h2>Why this is not the only layer</h2>
 * This layer depends on a developer routing a mutation through it. That is
 * exactly the discipline the reference application failed at - <strong>55 of 71
 * mutating routes forgot their permission check</strong>. The persistence guard
 * behind it catches what slips through, which is why it is load-bearing rather
 * than belt-and-braces: JPMS prevents accidental coupling but is defeatable by
 * reflection, as measured during the P4 review.
 *
 * <h2>Cost</h2>
 * A set lookup on an already-resolved entitlement. Signature verification happens
 * once when the entitlement is loaded - it costs about 1.5 ms and could not sit
 * in front of an accounting operation.
 */
public final class EntitlementGuard {

    private final ModuleStateProvider states;

    public EntitlementGuard(ModuleStateProvider states) {
        this.states = Objects.requireNonNull(states, "states");
    }

    /**
     * Authorise a mutation, or refuse it.
     *
     * @param moduleId  the module the operation belongs to
     * @param operation what the caller was trying to do, for the message
     * @throws ModuleNotWritableException if the module is not {@link ModuleState#ACTIVE}
     */
    public void requireWritable(ModuleId moduleId, String operation) {
        ModuleState state = states.moduleState(moduleId.value());
        if (!state.allowsMutation()) {
            throw new ModuleNotWritableException(moduleId, state, operation);
        }
    }

    /** Non-throwing form, for a UI deciding whether to disable a control. */
    public boolean isWritable(ModuleId moduleId) {
        return states.moduleState(moduleId.value()).allowsMutation();
    }

    /**
     * Whether a module's records may be read.
     *
     * <p>True for everything except {@link ModuleState#ABSENT}, where there is
     * nothing to read. Present so callers can decide what to show, not to gate
     * access.
     */
    public boolean isReadable(ModuleId moduleId) {
        return states.moduleState(moduleId.value()).allowsRead();
    }

    /** The current state, for display and diagnostics. */
    public ModuleState stateOf(ModuleId moduleId) {
        return states.moduleState(moduleId.value());
    }
}
