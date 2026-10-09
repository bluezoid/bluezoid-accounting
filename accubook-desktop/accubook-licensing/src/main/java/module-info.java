/**
 * Licensing: keys, entitlement tokens, cryptographic verification, machine
 * binding and subscription state.
 *
 * <p>Note what this module does <em>not</em> export to feature modules. The only
 * licensing type a module ever sees is {@code ModuleState}, which lives in
 * {@code accubook.module.api}. Nothing here - signatures, fingerprints,
 * activation - is visible to a feature module, by construction.
 */
module accubook.licensing {
    requires accubook.platform;
    requires accubook.module.api;
    requires java.net.http;

    exports com.accubook.licensing;
    exports com.accubook.licensing.key;
    exports com.accubook.licensing.token;
    exports com.accubook.licensing.machine;
    exports com.accubook.licensing.state;
    exports com.accubook.licensing.time;
}
