package com.accubook.integration.ui;

import com.accubook.app.company.CompanyService;
import com.accubook.inventory.Inventory;
import com.accubook.inventory.Item;
import com.accubook.inventory.ItemTaxRate;
import com.accubook.inventory.Unit;
import com.accubook.module.EntitlementGuard;
import com.accubook.tax.TaxCategory;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.inventory.InventoryScreens;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Inventory's screens: units, items with GST, warehouses and opening stock, through the screens. */
@DisplayName("Inventory screens")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class InventoryScreensIT {

    static Inventory inventory(CompanyService service) {
        return new Inventory(service.openCompanyTransactions(), new EntitlementGuard(service.moduleStates()));
    }

    @Nested
    @DisplayName("items")
    class Items extends FeatureScreenITBase {

        @Override
        ScreenProvider provider() {
            return new InventoryScreens();
        }

        @Override
        String entryId() {
            return InventoryScreens.ITEMS;
        }

        @Test
        @DisplayName("a unit, then an item at 18% from the day the books begin")
        void unitThenItem() throws Exception {
            clickOn("#unitSymbol").write("NOS").type(KeyCode.ENTER);
            write("Numbers").type(KeyCode.ENTER);
            type(KeyCode.ENTER);
            waitForText("#itemMessage", "Added unit NOS.");
            ComboBox<Unit> unit = lookup("#itemUnit").query();
            waitFor(() -> unit.getValue() != null);

            clickOn("#itemName").write("Table lamp");
            clickOn("#itemHsn").write("9405");
            clickOn("#gstRate").write("18");
            clickOn("#addItem");
            waitForText("#itemMessage", "Added Table lamp.");

            Item lamp = inventory(service).items().get(0);
            ItemTaxRate tax = inventory(service).taxOn(lamp.id(), LocalDate.of(2026, 4, 1)).orElseThrow();
            assertThat(tax.gstRate()).isEqualByComparingTo("18");
            assertThat(tax.category()).isEqualTo(TaxCategory.TAXABLE);
            assertThat(lamp.hsnSac()).isEqualTo("9405");
            TableView<?> table = lookup("#itemTable").queryTableView();
            waitFor(() -> table.getItems().size() == 1);
        }

        @Test
        @DisplayName("a rate that is not a GST rate on the date is refused before the item is created")
        void badRate() throws Exception {
            clickOn("#unitSymbol").write("KGS");
            clickOn("#unitName").write("Kilograms");
            clickOn("#addUnit");
            ComboBox<Unit> unit = lookup("#itemUnit").query();
            waitFor(() -> unit.getValue() != null);

            clickOn("#itemName").write("Rice");
            clickOn("#gstRate").write("13");
            clickOn("#addItem");
            waitFor(() -> !lookup("#itemMessage").queryAs(Label.class).getText().isBlank());

            assertThat(lookup("#itemMessage").queryAs(Label.class).getText()).startsWith("13% is not a GST rate");
            assertThat(inventory(service).items()).isEmpty();
        }
    }

    @Nested
    @DisplayName("warehouses and opening stock")
    class Stock extends FeatureScreenITBase {

        private Item lamp;

        @Override
        ScreenProvider provider() {
            return new InventoryScreens();
        }

        @Override
        String entryId() {
            return InventoryScreens.STOCK_SETUP;
        }

        @Override
        void seed() {
            Inventory inventory = inventory(service);
            Unit nos = inventory.createUnit("NOS", "Numbers", 0);
            lamp = inventory.createItem("Table lamp", com.accubook.inventory.ItemKind.GOODS, nos.id(), null, null,
                    "9405", null);
        }

        @Test
        @DisplayName("a warehouse, then opening stock dated the first day, valued at cost")
        void openingStock() throws Exception {
            waitForText("#openingDate", "Dated 01-04-2026");
            clickOn("#warehouseName").write("Main godown").type(KeyCode.ENTER).type(KeyCode.ENTER);
            waitForText("#stockMessage", "Added warehouse Main godown.");
            ComboBox<Item> item = lookup("#openingItem").query();
            waitFor(() -> !item.getItems().isEmpty());
            interact(() -> item.setValue(item.getItems().get(0)));
            clickOn("#openingQuantity").write("3");
            clickOn("#openingCost").write("33.3333");
            clickOn("#addOpening");
            waitForText("#stockMessage", "Added opening stock of Table lamp.");
            waitForText("#openingTotal", "Opening stock value: 100.00");

            var moves = inventory(service).movementsOf(lamp.id());
            assertThat(moves).singleElement().satisfies(m -> {
                assertThat(m.date()).isEqualTo(LocalDate.of(2026, 4, 1));
                assertThat(m.unitCost()).isEqualByComparingTo("33.3333");
            });
        }
    }
}
