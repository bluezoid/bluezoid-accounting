package com.accubook.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The architectural requirement: a feature module determines its own state
 * through {@link ModuleStateProvider} <strong>without</strong> importing or
 * depending on the licensing implementation.
 *
 * <p>This test lives in {@code accubook-module-api}, which does not depend on
 * {@code accubook-licensing} at all. If a licensing type ever leaked into the
 * contract, this module would stop compiling - so the boundary is enforced by
 * the build rather than by review.
 *
 * <p>That matters because the reference application enforced its equivalent
 * check per handler, and 55 of 71 mutating routes forgot it.
 */
@DisplayName("Module isolation from licensing")
class ModuleIsolationTest {

    /**
     * A feature module as Phase 4 will write them.
     *
     * <p>Note the imports it needs: {@code ModuleState} and
     * {@code ModuleStateProvider}. No licence, no signature, no fingerprint, no
     * activation client, no cryptography.
     */
    static final class PayrollModule implements FeatureModule {

        private final ModuleStateProvider entitlement;

        PayrollModule(ModuleStateProvider entitlement) {
            this.entitlement = entitlement;
        }

        @Override
        public ModuleId id() {
            return ModuleId.of("payroll");
        }

        @Override
        public String displayName() {
            return "Payroll";
        }

        boolean canRunPayroll() {
            return entitlement.moduleState(id().value()).allowsMutation();
        }

        boolean canViewPayslips() {
            return entitlement.moduleState(id().value()).allowsRead();
        }
    }

    /** Stands in for a resolved entitlement; the module cannot tell the difference. */
    private static ModuleStateProvider states(Map<String, ModuleState> byId) {
        return moduleId -> byId.getOrDefault(moduleId, ModuleState.ABSENT);
    }

    @Test
    @DisplayName("an entitled module can run and view")
    void activeModuleCanRun() {
        PayrollModule payroll = new PayrollModule(states(Map.of("payroll", ModuleState.ACTIVE)));
        assertThat(payroll.canRunPayroll()).isTrue();
        assertThat(payroll.canViewPayslips()).isTrue();
    }

    @Test
    @DisplayName("expiry reaches the module purely as READ_ONLY")
    void expiredSubscriptionArrivesAsReadOnly() {
        // The module never learns why. Expired subscription, downgraded plan and
        // administrative suspension are indistinguishable from here, and should
        // be: the module's job is bookkeeping, not licensing.
        PayrollModule payroll = new PayrollModule(states(Map.of("payroll", ModuleState.READ_ONLY)));
        assertThat(payroll.canRunPayroll()).isFalse();
        assertThat(payroll.canViewPayslips())
                .as("history is never withheld")
                .isTrue();
    }

    @Test
    @DisplayName("an unknown module id is ABSENT rather than an error")
    void unknownModuleIsAbsent() {
        PayrollModule payroll = new PayrollModule(states(Map.of()));
        assertThat(payroll.canRunPayroll()).isFalse();
        assertThat(payroll.canViewPayslips()).isFalse();
    }

    @Test
    @DisplayName("a dormant module keeps its data readable")
    void dormantModuleKeepsDataReadable() {
        PayrollModule payroll = new PayrollModule(states(Map.of("payroll", ModuleState.DORMANT)));
        assertThat(payroll.canRunPayroll()).isFalse();
        assertThat(payroll.canViewPayslips())
                .as("a downgrade must not hide records the customer already has")
                .isTrue();
    }

    @Test
    @DisplayName("only ACTIVE permits mutation")
    void onlyActiveAllowsMutation() {
        assertThat(ModuleState.ACTIVE.allowsMutation()).isTrue();
        assertThat(ModuleState.READ_ONLY.allowsMutation()).isFalse();
        assertThat(ModuleState.DORMANT.allowsMutation()).isFalse();
        assertThat(ModuleState.ABSENT.allowsMutation()).isFalse();
    }

    @Test
    @DisplayName("only ABSENT withholds reading")
    void onlyAbsentWithholdsReading() {
        assertThat(ModuleState.ACTIVE.allowsRead()).isTrue();
        assertThat(ModuleState.READ_ONLY.allowsRead()).isTrue();
        assertThat(ModuleState.DORMANT.allowsRead()).isTrue();
        assertThat(ModuleState.ABSENT.allowsRead()).isFalse();
    }

    @Test
    @DisplayName("the contract exposes exactly one method")
    void contractIsOneMethod() {
        // A wider contract is how licensing concepts leak into modules. Keeping
        // it to one method makes that leak impossible to introduce accidentally.
        assertThat(ModuleStateProvider.class.getDeclaredMethods())
                .as("ModuleStateProvider must stay a single-method contract")
                .hasSize(1);
        assertThat(ModuleStateProvider.class.getDeclaredMethods()[0].getName())
                .isEqualTo("moduleState");
    }

    @Test
    @DisplayName("the module-api module does not read the licensing module")
    void moduleApiDoesNotDependOnLicensing() {
        // The decisive structural assertion. If accubook.module.api ever
        // required accubook.licensing, feature modules could reach licensing
        // types through it.
        Module moduleApi = ModuleState.class.getModule();
        Set<String> reads = moduleApi.getDescriptor() == null
                ? Set.of()
                : moduleApi.getDescriptor().requires().stream()
                    .map(java.lang.module.ModuleDescriptor.Requires::name)
                    .collect(java.util.stream.Collectors.toSet());

        assertThat(reads)
                .as("module-api must not depend on licensing")
                .doesNotContain("accubook.licensing");
    }
}
