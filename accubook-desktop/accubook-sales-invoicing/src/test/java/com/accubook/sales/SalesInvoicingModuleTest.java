package com.accubook.sales;

import com.accubook.app.entitlement.TableOwners;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SalesInvoicingModuleTest {

    @Test
    @DisplayName("P4 boundary: no persistence, licensing, inventory or other feature module")
    void boundary() {
        ModuleDescriptor descriptor = SalesInvoicingModule.class.getModule().getDescriptor();
        Set<String> requires = descriptor.requires().stream()
                .map(ModuleDescriptor.Requires::name).collect(Collectors.toSet());

        assertThat(requires).contains("accubook.trade").doesNotContain("accubook.persistence",
                "accubook.licensing", "accubook.inventory", "accubook.app",
                "accubook.sales.invoicing", "accubook.purchase.billing");
    }

    @Test
    @DisplayName("claims exactly what the ownership catalogue says it owns")
    void ownership() {
        assertThat(new SalesInvoicingModule().ownedTables())
                .containsExactlyInAnyOrderElementsOf(TableOwners.tablesOf(SalesInvoicingModule.ID));
    }
}
