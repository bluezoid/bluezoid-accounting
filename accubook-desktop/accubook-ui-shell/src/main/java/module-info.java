/**
 * The JavaFX shell.
 *
 * <p>Note what is absent: this module does not require any feature module. The
 * shell renders navigation from whatever {@code FeatureModule} services are
 * present, so a build with fewer modules still compiles and runs. Enforced from
 * Phase 1 so it cannot rot before Phase 4 needs it.
 */
module accubook.ui.shell {
    requires accubook.platform;
    requires accubook.app;
    requires accubook.module.api;
    requires javafx.controls;
    requires javafx.graphics;
    requires java.sql;

    exports com.accubook.ui.shell;
}
