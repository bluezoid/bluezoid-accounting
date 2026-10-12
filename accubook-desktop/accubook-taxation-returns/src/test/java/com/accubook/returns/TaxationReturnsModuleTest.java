package com.accubook.returns;

import com.accubook.app.entitlement.TableOwners;
import com.accubook.module.ModuleId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.ModuleDescriptor;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaxationReturnsModuleTest {

    @Test
    @DisplayName("P4 boundary: reads documents through ModuleTransactions; requires no document module")
    void boundary() {
        Set<String> requires = TaxationReturnsModule.class.getModule().getDescriptor().requires().stream()
                .map(ModuleDescriptor.Requires::name).collect(Collectors.toSet());
        assertThat(requires).doesNotContain("accubook.persistence", "accubook.licensing", "accubook.app",
                "accubook.trade", "accubook.sales.invoicing", "accubook.purchase.billing", "accubook.inventory");
        assertThat(new TaxationReturnsModule().dependsOn())
                .containsExactlyInAnyOrder(ModuleId.of("sales-invoicing"), ModuleId.of("purchase-billing"));
    }

    @Test
    @DisplayName("claims exactly what the ownership catalogue says it owns")
    void ownership() {
        assertThat(new TaxationReturnsModule().ownedTables())
                .containsExactlyInAnyOrderElementsOf(TableOwners.tablesOf(TaxationReturnsModule.ID));
    }

    @Test
    @DisplayName("a period is a month or a financial-year quarter; fp is its last month")
    void periods() {
        assertThat(ReturnPeriod.month(2026, 5).fp()).isEqualTo("052026");
        assertThat(ReturnPeriod.quarter(2026, 4).fp()).isEqualTo("062026");
        assertThat(ReturnPeriod.quarter(2027, 1).to()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThatThrownBy(() -> ReturnPeriod.quarter(2026, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReturnPeriod(LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("JSON: key order kept, strings escaped, money to two places")
    void json() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("b", "say \"hi\"\n");
        m.put("a", Json.amount(new java.math.BigDecimal("10.5")));
        m.put("l", java.util.List.of(1, true));
        assertThat(Json.write(m)).isEqualTo("{\"b\":\"say \\\"hi\\\"\\n\",\"a\":10.50,\"l\":[1,true]}");
    }
}
