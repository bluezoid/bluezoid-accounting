/**
 * Shared kernel. Depends on nothing but the JDK, deliberately: every other
 * module may depend on this one, so a dependency here would leak everywhere.
 */
module accubook.platform {
    exports com.accubook.platform.money;
    exports com.accubook.platform.id;
}
