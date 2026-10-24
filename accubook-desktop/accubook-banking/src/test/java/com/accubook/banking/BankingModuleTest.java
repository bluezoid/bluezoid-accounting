package com.accubook.banking;

import com.accubook.app.entitlement.TableOwners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class BankingModuleTest {

    @Test
    @DisplayName("P4 boundary: the posting core only; no persistence, licensing or other feature")
    void boundary() {
        Set<String> requires = BankingModule.class.getModule().getDescriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name).collect(Collectors.toSet());
        assertThat(requires).contains("accubook.accounting").doesNotContain("accubook.persistence",
                "accubook.licensing", "accubook.app", "accubook.trade", "accubook.inventory",
                "accubook.sales.invoicing", "accubook.purchase.billing");
    }

    @Test
    @DisplayName("claims exactly what the ownership catalogue says it owns")
    void ownership() {
        assertThat(new BankingModule().ownedTables())
                .containsExactlyInAnyOrderElementsOf(TableOwners.tablesOf(BankingModule.ID));
    }
}
