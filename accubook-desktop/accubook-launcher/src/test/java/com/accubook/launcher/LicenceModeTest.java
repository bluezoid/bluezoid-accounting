package com.accubook.launcher;

import com.accubook.module.FeatureModule;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleStateProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LicenceModeTest {

    private static final FeatureModule INVENTORY = new FeatureModule() {
        @Override
        public ModuleId id() {
            return ModuleId.of("inventory");
        }

        @Override
        public String displayName() {
            return "Inventory";
        }
    };

    private static final ModuleRegistry PACKAGED = ModuleRegistry.discover(List.of(INVENTORY));

    @Test
    @DisplayName("P7-7 release gate: a release build refuses to start on the development entitlement")
    void releaseRefuses() {
        assertThatThrownBy(() -> LicenceMode.select("release", PACKAGED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release build");
    }

    @Test
    @DisplayName("a development build activates exactly what it packages, plus the kernel")
    void developmentActivatesPackaged() {
        ModuleStateProvider states = LicenceMode.select("development", PACKAGED);

        assertThat(states.moduleState("inventory")).isEqualTo(ModuleState.ACTIVE);
        assertThat(states.moduleState("accounting")).isEqualTo(ModuleState.ACTIVE);
        assertThat(states.moduleState("payroll")).isEqualTo(ModuleState.ABSENT);
    }

    @Test
    @DisplayName("an unset channel is a development build")
    void unsetIsDevelopment() {
        assertThat(LicenceMode.select(null, PACKAGED)).isInstanceOf(DevelopmentEntitlement.class);
    }
}
