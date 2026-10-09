package com.accubook.module;

/**
 * A module graph could not be validated.
 *
 * <p>Thrown at build time and at startup. Failing here is deliberate: a malformed
 * module graph should stop an installer being produced, not surface as a puzzling
 * runtime error on a customer's machine.
 */
public class ModuleRegistryException extends RuntimeException {

    public ModuleRegistryException(String message) {
        super(message);
    }
}
