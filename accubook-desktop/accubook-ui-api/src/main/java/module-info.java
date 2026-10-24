/**
 * The screen contract (Phase 11S): what a feature's screens may use, and the
 * shared controls every screen builds from. The shell finds screens through
 * {@code ScreenProvider} services and never requires a feature; a feature's
 * screens require this module and their feature, nothing else.
 */
module accubook.ui.api {
    requires transitive accubook.platform;
    requires transitive accubook.module.api;
    requires transitive accubook.tax;
    requires transitive javafx.controls;
    requires transitive java.sql;

    exports com.accubook.ui.api;

}
