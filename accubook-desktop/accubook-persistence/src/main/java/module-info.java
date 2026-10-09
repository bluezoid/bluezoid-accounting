/**
 * SQLite persistence: connection management, pragmas, migrations, and the
 * per-company database topology of decision B-6.
 */
module accubook.persistence {
    requires accubook.platform;
    requires java.sql;
    requires org.xerial.sqlitejdbc;

    exports com.accubook.persistence.connection;
    exports com.accubook.persistence.migration;
    exports com.accubook.persistence.paths;
    exports com.accubook.persistence.location;
    exports com.accubook.persistence.lock;
    exports com.accubook.persistence.guard;
}
