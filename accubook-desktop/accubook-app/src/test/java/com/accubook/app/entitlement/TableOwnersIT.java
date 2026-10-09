package com.accubook.app.entitlement;

import com.accubook.app.schema.Schemas;
import com.accubook.module.FeatureModule;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.persistence.connection.Database;
import com.accubook.persistence.migration.MigrationRunner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ownership of every shipped table, and the guard's use of it.
 *
 * <p>This is the test that bounds the guard's one fail-open case: an unknown
 * table is permitted as kernel, so no shipped table may be unknown.
 */
@DisplayName("Table ownership")
class TableOwnersIT {

    private static Set<String> tablesCreatedBy(Path dir, String file, boolean company) throws SQLException {
        try (Database database = Database.open(dir.resolve(file))) {
            new MigrationRunner(company ? Schemas.companyDatabase() : Schemas.appDatabase(),
                    dir.resolve("backups-" + file)).migrate(database);
            Set<String> tables = new HashSet<>();
            try (var connection = database.openReadConnection();
                 Statement s = connection.createStatement();
                 ResultSet rows = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' "
                         + "AND name NOT LIKE 'sqlite_%'")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            return tables;
        }
    }

    @Test
    @DisplayName("every table the migrations create is classified, and nothing is classified that does not exist")
    void everyShippedTableClassified(@TempDir Path dir) throws SQLException {
        Set<String> shipped = new HashSet<>(tablesCreatedBy(dir, "company.db", true));
        shipped.addAll(tablesCreatedBy(dir, "app.db", false));

        assertThat(TableOwners.all()).containsExactlyInAnyOrderElementsOf(shipped);
    }

    @Test
    @DisplayName("no table is both kernel and feature-owned")
    void disjoint() {
        for (String table : TableOwners.KERNEL) {
            assertThat(TableOwners.featureOwnerOf(table)).as(table).isEmpty();
        }
    }

    @Test
    @DisplayName("a build without the inventory module still refuses writes to inventory tables")
    void absentModuleTablesRefused() {
        // A BASIC build: nothing installed, inventory DORMANT. Before the
        // catalogue these tables had no owner and were writable as kernel.
        ModuleWriteAuthorization guard = new ModuleWriteAuthorization(
                ModuleRegistry.discover(List.of()),
                id -> id.equals("inventory") ? ModuleState.DORMANT : ModuleState.ACTIVE);

        assertThat(guard.refuse("stock")).hasValueSatisfying(reason -> assertThat(reason).contains("inventory"));
        assertThat(guard.refuse("stock_movement")).isPresent();
        assertThat(guard.refuse("voucher")).isEmpty();
    }

    @Test
    @DisplayName("a module claiming a kernel table cannot gate it")
    void rogueClaimIgnored() {
        FeatureModule rogue = new FeatureModule() {
            @Override
            public ModuleId id() {
                return ModuleId.of("rogue");
            }

            @Override
            public String displayName() {
                return "Rogue";
            }

            @Override
            public Set<String> ownedTables() {
                return Set.of("voucher_entry");
            }
        };
        ModuleWriteAuthorization guard = new ModuleWriteAuthorization(
                ModuleRegistry.discover(List.of(rogue)), id -> ModuleState.READ_ONLY);

        assertThat(guard.refuse("voucher_entry")).isEmpty();
    }
}
