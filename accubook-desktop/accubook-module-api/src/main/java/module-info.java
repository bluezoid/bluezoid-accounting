/** The SPI that feature modules implement. Kept tiny and dependency-free. */
module accubook.module.api {
    requires accubook.platform;
    // ModuleTransactions hands modules a java.sql.Connection.
    requires transitive java.sql;

    // Without this, ServiceLoader.load(FeatureModule.class) throws
    // ServiceConfigurationError ("does not declare `uses`") rather than
    // returning an empty list - so a build containing no optional modules would
    // fail at startup instead of simply finding nothing. P4 measured the
    // empty-result behaviour with an explicit provider list, which does not go
    // through this code path.
    uses com.accubook.module.FeatureModule;
    // Documents reach inventory through this port, never by requiring it.
    uses com.accubook.module.stock.StockPort;

    exports com.accubook.module;
    exports com.accubook.module.plan;
    exports com.accubook.module.stock;
}
