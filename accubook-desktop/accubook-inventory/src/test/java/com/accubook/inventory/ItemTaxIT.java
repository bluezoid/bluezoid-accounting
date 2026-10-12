package com.accubook.inventory;

import com.accubook.app.company.CompanyService;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleNotWritableException;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleTransactions;
import com.accubook.module.stock.StockPort;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePacks;
import com.accubook.tax.TaxCategory;
import com.accubook.tax.TrustedRulePackKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P9-2: an item's GST category and rates, effective-dated. */
@DisplayName("Item GST")
class ItemTaxIT {

    private static final RulePack RULES = RulePacks.bundled(TrustedRulePackKeys.forThisBuild()).pack();
    private static final LocalDate APRIL_2025 = LocalDate.of(2025, 4, 1);
    private static final LocalDate GST_2_0 = LocalDate.of(2025, 9, 22);

    @TempDir
    Path dir;

    private final Map<String, ModuleState> states = new HashMap<>();
    private CompanyService service;
    private ModuleTransactions transactions;
    private Inventory inventory;
    private Item car;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of(new InventoryModule())),
                id -> states.getOrDefault(id, ModuleState.ACTIVE));
        service.openCompany(service.createCompany("Asha Traders").id());
        transactions = service.openCompanyTransactions();
        inventory = new Inventory(transactions, new EntitlementGuard(service.moduleStates()));
        Unit nos = inventory.createUnit("NOS", "Numbers", 0);
        car = inventory.createItem("Small car", ItemKind.GOODS, nos.id(), null, null, "8703", null);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("GST 2.0: 28% + 1% cess until 21 Sep 2025, 18% from 22 Sep - each date reads its own rate")
    void effectiveDated() {
        inventory.setTax(car.id(), APRIL_2025, TaxCategory.TAXABLE, d("28"), d("1"), RULES);
        inventory.setTax(car.id(), GST_2_0, TaxCategory.TAXABLE, d("18"), null, RULES);

        assertThat(inventory.taxOn(car.id(), GST_2_0.minusDays(1))).hasValueSatisfying(t -> {
            assertThat(t.gstRate()).isEqualByComparingTo("28");
            assertThat(t.cessRate()).isEqualByComparingTo("1");
            assertThat(t.hsnSac()).isEqualTo("8703");
            assertThat(t.rulePackVersion()).isEqualTo(RULES.version());
        });
        assertThat(inventory.taxOn(car.id(), GST_2_0)).hasValueSatisfying(t -> {
            assertThat(t.gstRate()).isEqualByComparingTo("18");
            assertThat(t.cessRate()).isEqualByComparingTo("0");
        });
        assertThat(inventory.taxOn(car.id(), APRIL_2025.minusDays(1))).as("nothing recorded yet").isEmpty();
        assertThat(inventory.taxHistory(car.id())).extracting(ItemTaxRate::effectiveFrom)
                .containsExactly(APRIL_2025, GST_2_0);
    }

    @Test
    @DisplayName("recording the same date again corrects that entry rather than adding one")
    void correctsSameDate() {
        inventory.setTax(car.id(), GST_2_0, TaxCategory.TAXABLE, d("5"), null, RULES);
        inventory.setTax(car.id(), GST_2_0, TaxCategory.TAXABLE, d("18"), null, RULES);

        assertThat(inventory.taxHistory(car.id())).singleElement()
                .satisfies(t -> assertThat(t.gstRate()).isEqualByComparingTo("18"));
    }

    @Test
    @DisplayName("a rate the rule pack does not have on that date is refused; untaxed categories carry none")
    void validated() {
        assertThatThrownBy(() -> inventory.setTax(car.id(), GST_2_0, TaxCategory.TAXABLE, d("28"), null, RULES))
                .isInstanceOf(InventoryException.class).hasMessageContaining("28% is not a GST rate");
        assertThatThrownBy(() -> inventory.setTax(car.id(), GST_2_0, TaxCategory.EXEMPT, d("5"), null, RULES))
                .hasMessageContaining("carries no GST");
        inventory.setTax(car.id(), GST_2_0, TaxCategory.NIL_RATED, null, null, RULES);
        assertThat(inventory.taxOn(car.id(), GST_2_0)).hasValueSatisfying(t ->
                assertThat(t.category()).isEqualTo(TaxCategory.NIL_RATED));
    }

    @Test
    @DisplayName("a read-only inventory module cannot change an item's GST")
    void entitlement() {
        states.put("inventory", ModuleState.READ_ONLY);
        Inventory readOnly = new Inventory(transactions, new EntitlementGuard(service.moduleStates()));

        assertThatThrownBy(() -> readOnly.setTax(car.id(), GST_2_0, TaxCategory.TAXABLE, d("18"), null, RULES))
                .isInstanceOf(ModuleNotWritableException.class);
    }

    @Test
    @DisplayName("documents see the same rate through the stock port")
    void throughStockPort() throws Exception {
        inventory.setTax(car.id(), GST_2_0, TaxCategory.TAXABLE, d("18"), null, RULES);

        StockPort.ItemTax tax = transactions.read(c -> new InventoryStockPort().taxOn(c, car.id(), GST_2_0)).orElseThrow();
        assertThat(tax.category()).isEqualTo("TAXABLE");
        assertThat(tax.gstRate()).isEqualByComparingTo("18");
        assertThat(tax.hsnSac()).isEqualTo("8703");
    }
}
