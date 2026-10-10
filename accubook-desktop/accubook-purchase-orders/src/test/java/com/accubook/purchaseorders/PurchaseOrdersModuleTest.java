package com.accubook.purchaseorders;

import com.accubook.app.entitlement.TableOwners;
import com.accubook.module.ModuleId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseOrdersModuleTest {

    @Test
    @DisplayName("P4 boundary: only the trade library; inventory reached through StockPort, declared as a dependency")
    void boundary() {
        Set<String> requires = PurchaseOrdersModule.class.getModule().getDescriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name).collect(Collectors.toSet());
        assertThat(requires).contains("accubook.trade").doesNotContain("accubook.persistence",
                "accubook.licensing", "accubook.inventory", "accubook.app", "accubook.sales.invoicing",
                "accubook.purchase.billing");
        assertThat(new PurchaseOrdersModule().dependsOn()).containsExactly(ModuleId.of("inventory"));
    }

    @Test
    @DisplayName("claims exactly what the ownership catalogue says it owns")
    void ownership() {
        assertThat(new PurchaseOrdersModule().ownedTables())
                .containsExactlyInAnyOrderElementsOf(TableOwners.tablesOf(PurchaseOrdersModule.ID));
    }
}
