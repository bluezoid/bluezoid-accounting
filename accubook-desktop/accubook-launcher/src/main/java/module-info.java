/**
 * Composition root: the only module that knows how the application is assembled.
 *
 * <p>{@code uses FeatureModule} is what makes {@code jlink --bind-services}
 * meaningful - without the service binding the SQLite driver is not resolved in
 * a linked image and {@code DriverManager} reports "No suitable driver".
 */
module accubook.launcher {
    requires accubook.platform;
    requires accubook.app;
    requires accubook.ui.shell;
    requires accubook.module.api;
    requires javafx.graphics;

    uses com.accubook.module.FeatureModule;

    exports com.accubook.launcher;
}
