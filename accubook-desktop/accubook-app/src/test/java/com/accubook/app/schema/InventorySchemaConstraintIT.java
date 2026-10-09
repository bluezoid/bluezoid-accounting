package com.accubook.app.schema;

import com.accubook.persistence.connection.Database;
import com.accubook.persistence.migration.MigrationRunner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventory rules the database enforces on its own - so they hold even for a
 * write that never went near the inventory service.
 */
@DisplayName("Inventory schema constraints")
class InventorySchemaConstraintIT {

    @TempDir
    Path dir;

    private Database database;

    @BeforeEach
    void setUp() throws SQLException {
        database = Database.open(dir.resolve("company.db"));
        new MigrationRunner(Schemas.companyDatabase(), dir.resolve("backups")).migrate(database);
        exec("INSERT INTO unit (id, symbol, name, decimals, created_at, updated_at) "
                + "VALUES ('u1', 'NOS', 'Numbers', 0, 0, 0)");
        exec("INSERT INTO item (id, unit_id, name, kind, created_at, updated_at) "
                + "VALUES ('rice', 'u1', 'Rice', 'GOODS', 0, 0)");
        exec("INSERT INTO item (id, unit_id, name, kind, created_at, updated_at) "
                + "VALUES ('svc', 'u1', 'Delivery', 'SERVICE', 0, 0)");
        exec("INSERT INTO warehouse (id, name, created_at, updated_at) VALUES ('w1', 'Main', 0, 0)");
        exec("INSERT INTO warehouse (id, name, created_at, updated_at) VALUES ('w2', 'Shop', 0, 0)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        database.close();
    }

    private void exec(String sql) throws SQLException {
        database.inTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    private static String movement(String id, String type, String from, String to) {
        return "INSERT INTO stock_movement (id, item_id, movement_type, from_warehouse_id, "
                + "to_warehouse_id, quantity_e4, movement_date, created_at) VALUES ('" + id
                + "', 'rice', '" + type + "', " + from + ", " + to + ", 10000, '2026-05-01', 0)";
    }

    @Test
    @DisplayName("P7-4: stock can never be negative, whatever wrote it")
    void stockNeverNegative() {
        assertThatThrownBy(() -> exec("INSERT INTO stock VALUES ('rice', 'w1', -1, 0)"))
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("a sale must name a source and no destination - it can never add stock")
    void saleDirection() {
        assertThatThrownBy(() -> exec(movement("m1", "SALE", "NULL", "'w1'")))
                .hasMessageContaining("CHECK");
        assertThatCode(() -> exec(movement("m2", "SALE", "'w1'", "NULL"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a purchase return goes out - the movement the reference cannot represent")
    void purchaseReturnGoesOut() {
        assertThatThrownBy(() -> exec(movement("m1", "PURCHASE_RETURN", "NULL", "'w1'")))
                .hasMessageContaining("CHECK");
        assertThatCode(() -> exec(movement("m2", "PURCHASE_RETURN", "'w1'", "NULL")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a transfer needs two different warehouses")
    void transferNeedsTwo() {
        assertThatThrownBy(() -> exec(movement("m1", "TRANSFER", "'w1'", "'w1'")))
                .hasMessageContaining("CHECK");
        assertThatThrownBy(() -> exec(movement("m2", "TRANSFER", "'w1'", "NULL")))
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("an unknown movement type and a zero quantity are refused")
    void unknownTypeAndZero() {
        assertThatThrownBy(() -> exec(movement("m1", "RETURN", "NULL", "'w1'")))
                .hasMessageContaining("CHECK");
        assertThatThrownBy(() -> exec("INSERT INTO stock_movement (id, item_id, movement_type, "
                + "to_warehouse_id, quantity_e4, movement_date, created_at) "
                + "VALUES ('m2', 'rice', 'PURCHASE', 'w1', 0, '2026-05-01', 0)"))
                .hasMessageContaining("CHECK");
    }

    @Test
    @DisplayName("the movement log is append-only")
    void appendOnly() throws SQLException {
        exec(movement("m1", "PURCHASE", "NULL", "'w1'"));
        assertThatThrownBy(() -> exec("UPDATE stock_movement SET quantity_e4 = 1"))
                .hasMessageContaining("cannot be changed");
        assertThatThrownBy(() -> exec("DELETE FROM stock_movement"))
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    @DisplayName("a service never has stock or movements")
    void servicesHaveNoStock() {
        assertThatThrownBy(() -> exec("INSERT INTO stock VALUES ('svc', 'w1', 0, 0)"))
                .hasMessageContaining("service");
        assertThatThrownBy(() -> exec("INSERT INTO stock_movement (id, item_id, movement_type, "
                + "to_warehouse_id, quantity_e4, movement_date, created_at) "
                + "VALUES ('m1', 'svc', 'PURCHASE', 'w1', 10000, '2026-05-01', 0)"))
                .hasMessageContaining("service");
    }

    @Test
    @DisplayName("an item with movements keeps its kind and unit")
    void kindAndUnitFixedOnceMoved() throws SQLException {
        exec("INSERT INTO unit (id, symbol, name, decimals, created_at, updated_at) "
                + "VALUES ('u2', 'KG', 'Kilograms', 3, 0, 0)");
        assertThatCode(() -> exec("UPDATE item SET unit_id = 'u2' WHERE id = 'rice'"))
                .as("allowed before any movement").doesNotThrowAnyException();

        exec(movement("m1", "PURCHASE", "NULL", "'w1'"));

        assertThatThrownBy(() -> exec("UPDATE item SET unit_id = 'u1' WHERE id = 'rice'"))
                .hasMessageContaining("kind and unit cannot change");
        assertThatThrownBy(() -> exec("UPDATE item SET kind = 'SERVICE' WHERE id = 'rice'"))
                .hasMessageContaining("kind and unit cannot change");
        assertThatCode(() -> exec("UPDATE item SET name = 'Basmati Rice' WHERE id = 'rice'"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("names are unique ignoring case")
    void namesIgnoreCase() {
        assertThatThrownBy(() -> exec("INSERT INTO item (id, unit_id, name, kind, created_at, updated_at) "
                + "VALUES ('x', 'u1', 'RICE', 'GOODS', 0, 0)"))
                .hasMessageContaining("UNIQUE");
        assertThatThrownBy(() -> exec("INSERT INTO unit (id, symbol, name, decimals, created_at, updated_at) "
                + "VALUES ('x', 'nos', 'n', 0, 0, 0)"))
                .hasMessageContaining("UNIQUE");
    }
}
