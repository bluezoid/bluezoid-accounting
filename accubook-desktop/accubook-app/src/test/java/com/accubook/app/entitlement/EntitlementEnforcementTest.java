package com.accubook.app.entitlement;

import com.accubook.module.FeatureModule;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleStateProvider;
import com.accubook.persistence.connection.Database;
import com.accubook.persistence.guard.UnauthorizedWriteException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entitlement enforcement end to end: registry ownership, module state and the
 * persistence guard together.
 *
 * <p>The individual pieces are tested in their own modules. What is tested here
 * is the join - that a module going READ_ONLY actually stops a write reaching
 * SQLite, and that the customer's existing records survive it.
 */
class EntitlementEnforcementTest {

    @TempDir
    Path directory;

    private Database database;
    private final Map<String, ModuleState> states = new HashMap<>();

    /** A payroll module owning two tables, as a real one would. */
    private static final FeatureModule PAYROLL = new FeatureModule() {
        @Override
        public ModuleId id() {
            return ModuleId.of("payroll");
        }

        @Override
        public String displayName() {
            return "Payroll";
        }

        @Override
        public Set<String> ownedTables() {
            return Set.of("payroll_runs", "payslips");
        }
    };

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(directory.resolve("company.db"));
        database.inUnguardedTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE payroll_runs (id INTEGER PRIMARY KEY, amount INTEGER)");
                statement.execute("CREATE TABLE payslips (id INTEGER PRIMARY KEY, run_id INTEGER)");
                statement.execute("CREATE TABLE ledger_entries (id INTEGER PRIMARY KEY, amount INTEGER)");
                statement.execute("CREATE TABLE companies (id INTEGER PRIMARY KEY, name TEXT)");
                // Records written while the module was still entitled.
                statement.execute("INSERT INTO payroll_runs VALUES (1, 50000)");
                statement.execute("INSERT INTO payslips VALUES (1, 1)");
            }
            return null;
        });

        ModuleRegistry registry = ModuleRegistry.discover(java.util.List.of(PAYROLL));
        ModuleStateProvider provider = moduleId ->
                states.getOrDefault(moduleId, ModuleState.ABSENT);
        database.authorizeWith(new ModuleWriteAuthorization(registry, provider));
    }

    @AfterEach
    void tearDown() throws SQLException {
        database.close();
    }

    private void insertPayrollRun() throws SQLException {
        database.inTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                return s.executeUpdate("INSERT INTO payroll_runs VALUES (2, 60000)");
            }
        });
    }

    private long countIn(String table) throws SQLException {
        try (Connection connection = database.openReadConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    @DisplayName("an ACTIVE module writes normally")
    void activeModuleCanWrite() throws SQLException {
        states.put("payroll", ModuleState.ACTIVE);

        insertPayrollRun();

        assertEquals(2, countIn("payroll_runs"));
    }

    @Test
    @DisplayName("a READ_ONLY module cannot write, whatever called it")
    void readOnlyModuleCannotWrite() throws SQLException {
        states.put("payroll", ModuleState.READ_ONLY);

        UnauthorizedWriteException e = assertThrows(UnauthorizedWriteException.class, this::insertPayrollRun);

        assertAllOf(e, "payroll", "READ_ONLY");
        assertEquals(1, countIn("payroll_runs"), "nothing was written");
    }

    @Test
    @DisplayName("a DORMANT module cannot write")
    void dormantModuleCannotWrite() {
        states.put("payroll", ModuleState.DORMANT);

        assertThrows(UnauthorizedWriteException.class, this::insertPayrollRun);
    }

    @Test
    @DisplayName("every table the module owns is protected, not just the first")
    void allOwnedTablesProtected() {
        states.put("payroll", ModuleState.READ_ONLY);

        assertThrows(UnauthorizedWriteException.class, () ->
                database.inTransaction(connection -> {
                    try (Statement s = connection.createStatement()) {
                        return s.executeUpdate("INSERT INTO payslips VALUES (2, 1)");
                    }
                }));
    }

    @Test
    @DisplayName("a downgraded customer keeps their records and can still read them")
    void downgradePreservesAndPermitsReading() throws SQLException {
        states.put("payroll", ModuleState.READ_ONLY);

        assertThrows(UnauthorizedWriteException.class, this::insertPayrollRun);

        // The whole point of READ_ONLY rather than deletion: the books are the
        // customer's statutory records, and remain available.
        assertEquals(1, countIn("payroll_runs"));
        assertEquals(1, countIn("payslips"));
    }

    @Test
    @DisplayName("posted ledger entries stay writable when payroll is locked")
    void ledgerIsNotOwnedByPayroll() throws SQLException {
        states.put("payroll", ModuleState.READ_ONLY);

        // A payroll journal entry is a *ledger* entry. If payroll owned the
        // ledger, a downgrade would stop the trial balance being maintained and
        // the books would cease to balance.
        database.inTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                return s.executeUpdate("INSERT INTO ledger_entries VALUES (1, 50000)");
            }
        });

        assertEquals(1, countIn("ledger_entries"));
    }

    @Test
    @DisplayName("kernel tables stay writable so the application can still open")
    void kernelTablesRemainWritable() throws SQLException {
        states.put("payroll", ModuleState.READ_ONLY);

        database.inTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                return s.executeUpdate("INSERT INTO companies VALUES (1, 'Acme')");
            }
        });

        assertEquals(1, countIn("companies"));
    }

    @Test
    @DisplayName("an unknown module defaults to ABSENT and cannot write")
    void unknownModuleCannotWrite() {
        // states is empty: the provider answers ABSENT.
        assertThrows(UnauthorizedWriteException.class, this::insertPayrollRun);
    }

    @Test
    @DisplayName("the refusal explains what to do about it")
    void refusalIsActionable() {
        states.put("payroll", ModuleState.READ_ONLY);

        UnauthorizedWriteException e = assertThrows(UnauthorizedWriteException.class, this::insertPayrollRun);

        assertTrue(e.getMessage().contains("readable and exportable"),
                "a customer must be told their data is not lost: " + e.getMessage());
    }

    private static void assertAllOf(Exception e, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(e.getMessage().contains(fragment),
                    "expected '" + fragment + "' in: " + e.getMessage());
        }
    }
}
