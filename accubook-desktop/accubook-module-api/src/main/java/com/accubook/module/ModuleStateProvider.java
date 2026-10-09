package com.accubook.module;

/**
 * The one question a feature module may ask about its own licensing.
 *
 * <p>Deliberately a single method taking a module id and returning an enum.
 * Everything behind it - Ed25519 verification, subscription dates, machine
 * binding, activation - is invisible from here, so a module cannot accidentally
 * couple itself to the licensing implementation, and licensing can be reworked
 * without touching a single module.
 *
 * <p>Implementations must be cheap enough to call freely: the entitlement is
 * verified once when it is loaded, and this method reads the already-resolved
 * result. Signature verification costs about 1.5 ms, which is far too slow to
 * sit in front of an accounting operation.
 */
@FunctionalInterface
public interface ModuleStateProvider {

    /**
     * The state of {@code moduleId} right now.
     *
     * <p>Never throws for an unknown id - an unrecognised module is
     * {@link ModuleState#ABSENT}, which is the safe answer.
     */
    ModuleState moduleState(String moduleId);
}
