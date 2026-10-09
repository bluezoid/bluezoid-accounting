package com.accubook.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryModuleTest {

    private static final LocalDate DAY = LocalDate.of(2026, 5, 1);

    @Test
    @DisplayName("P4 boundary: the module reaches neither persistence nor licensing")
    void boundary() {
        ModuleDescriptor descriptor = InventoryModule.class.getModule().getDescriptor();
        Set<String> requires = descriptor.requires().stream()
                .map(ModuleDescriptor.Requires::name).collect(Collectors.toSet());

        assertThat(requires).doesNotContain("accubook.persistence", "accubook.licensing",
                "accubook.app", "accubook.accounting");
        assertThat(descriptor.provides()).anySatisfy(p ->
                assertThat(p.service()).isEqualTo("com.accubook.module.FeatureModule"));
    }

    @Test
    @DisplayName("owns exactly the inventory tables and declares historical views over them")
    void ownership() {
        InventoryModule module = new InventoryModule();
        assertThat(module.ownedTables()).containsExactlyInAnyOrder(
                "unit", "item_category", "item", "warehouse", "stock", "stock_movement");
        assertThat(module.historicalViews())
                .allSatisfy(view -> assertThat(module.ownedTables()).contains(view.table()));
    }

    @Test
    @DisplayName("each movement type names exactly the warehouses its direction needs")
    void directionRules() {
        assertThatThrownBy(() -> new MovementRequest(MovementType.SALE, "i", null, "w", BigDecimal.ONE,
                null, DAY, null, null, null))
                .isInstanceOf(InventoryException.class).hasMessageContaining("takes stock out");
        assertThatThrownBy(() -> new MovementRequest(MovementType.PURCHASE, "i", "w", null, BigDecimal.ONE,
                null, DAY, null, null, null))
                .isInstanceOf(InventoryException.class).hasMessageContaining("brings stock in");
        assertThatThrownBy(() -> MovementRequest.transfer("i", "w", "w", BigDecimal.ONE, DAY, null))
                .isInstanceOf(InventoryException.class).hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("a movement needs a positive quantity, a non-negative cost and a whole source reference")
    void requestValidation() {
        assertThatThrownBy(() -> MovementRequest.in(MovementType.PURCHASE, "i", "w", BigDecimal.ZERO, null, DAY, null))
                .isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> MovementRequest.in(MovementType.PURCHASE, "i", "w", BigDecimal.ONE,
                new BigDecimal("-1"), DAY, null)).isInstanceOf(InventoryException.class);
        assertThatThrownBy(() -> new MovementRequest(MovementType.PURCHASE, "i", null, "w", BigDecimal.ONE,
                null, DAY, "SALES_INVOICE", null, null)).isInstanceOf(InventoryException.class);
    }

    @Test
    @DisplayName("every type has exactly one direction, and only a transfer needs both warehouses")
    void everyTypeHasDirection() {
        for (MovementType type : MovementType.values()) {
            assertThat(type.needsSource() || type.needsDestination()).isTrue();
            assertThat(type.needsSource() && type.needsDestination())
                    .isEqualTo(type == MovementType.TRANSFER);
        }
    }
}
