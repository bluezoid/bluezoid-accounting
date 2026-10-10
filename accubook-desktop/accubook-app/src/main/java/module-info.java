/** Use-case layer. Owns the transaction boundary; domain code never begins one. */
module accubook.app {
    requires accubook.platform;
    requires accubook.persistence;
    requires accubook.module.api;
    requires accubook.licensing;
    requires transitive accubook.tax;
    requires java.sql;

    exports com.accubook.app.company;
    exports com.accubook.app.provisioning;
    exports com.accubook.app.entitlement;
    exports com.accubook.app.module;
}
