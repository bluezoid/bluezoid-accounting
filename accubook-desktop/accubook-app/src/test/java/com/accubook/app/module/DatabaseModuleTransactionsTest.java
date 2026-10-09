package com.accubook.app.module;

import com.accubook.app.entitlement.ModuleWriteAuthorization;
import com.accubook.module.FeatureModule;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.persistence.connection.Database;
import com.accubook.persistence.guard.UnauthorizedWriteException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.module.ModuleDescriptor;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The port feature modules use for storage: guarded writes, unwritable reads.
 */
class DatabaseModuleTransactionsTest {

    @TempDir
    Path directory;

    private Database database;
    private ModuleTransactions transactions;
    private final Map<String, ModuleState> states = new HashMap<>();

    private static final FeatureModule STOCK = new FeatureModule() {
        @Override
        public ModuleId id() {
            return ModuleId.of("inventory");
        }

        @Override
        public String displayName() {
            return "Inventory";
        }

        @Override
        public Set<String> ownedTables() {
            return Set.of("stock");
        }
    };

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(directory.resolve("company.db"));
        database.inUnguardedTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute("CREATE TABLE stock (id INTEGER PRIMARY KEY, quantity_e4 INTEGER) STRICT");
                s.execute("INSERT INTO stock VALUES (1, 50000)");
            }
            return null;
        });
        database.authorizeWith(new ModuleWriteAuthorization(
                ModuleRegistry.discover(List.of(STOCK)),
                id -> states.getOrDefault(id, ModuleState.ABSENT)));
        transactions = new DatabaseModuleTransactions(database);
    }

    @AfterEach
    void tearDown() throws SQLException {
        database.close();
    }

    private int writeStock() throws SQLException {
        return transactions.inTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                return s.executeUpdate("UPDATE stock SET quantity_e4 = 0 WHERE id = 1");
            }
        });
    }

    private long quantity() throws SQLException {
        return transactions.read(connection -> {
            try (Statement s = connection.createStatement();
                 ResultSet rows = s.executeQuery("SELECT quantity_e4 FROM stock WHERE id = 1")) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }

    @Test
    @DisplayName("an ACTIVE module writes through the port")
    void activeModuleWrites() throws SQLException {
        states.put("inventory", ModuleState.ACTIVE);

        assertEquals(1, writeStock());
        assertEquals(0, quantity());
    }

    @Test
    @DisplayName("writes through the port are guarded: a READ_ONLY module is refused")
    void writesAreGuarded() throws SQLException {
        states.put("inventory", ModuleState.READ_ONLY);

        assertThrows(UnauthorizedWriteException.class, this::writeStock);
        assertEquals(50000, quantity(), "records stay readable and unchanged");
    }

    @Test
    @DisplayName("the read connection refuses writes, even to an ACTIVE module's table")
    void readCannotWrite() throws SQLException {
        states.put("inventory", ModuleState.ACTIVE);

        assertThrows(SQLException.class, () -> transactions.read(connection -> {
            try (Statement s = connection.createStatement()) {
                return s.executeUpdate("UPDATE stock SET quantity_e4 = 0");
            }
        }));
        assertEquals(50000, quantity());
    }

    @Test
    @DisplayName("the read connection cannot switch query_only off")
    void readCannotDisableQueryOnly() throws SQLException {
        assertThrows(UnauthorizedWriteException.class, () -> transactions.read(connection -> {
            try (Statement s = connection.createStatement()) {
                return s.execute("PRAGMA query_only = 0");
            }
        }));
    }

    @Test
    @DisplayName("module-api, the only thing a feature module sees, does not depend on persistence")
    void moduleApiDoesNotRequirePersistence() {
        ModuleDescriptor descriptor = ModuleTransactions.class.getModule().getDescriptor();
        Set<String> requires = descriptor.requires().stream()
                .map(ModuleDescriptor.Requires::name)
                .collect(Collectors.toSet());

        assertFalse(requires.contains("accubook.persistence"), requires.toString());
        assertFalse(requires.contains("accubook.licensing"), requires.toString());
    }
}
