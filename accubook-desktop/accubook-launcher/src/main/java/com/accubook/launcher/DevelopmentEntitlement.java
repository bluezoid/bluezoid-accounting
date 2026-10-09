package com.accubook.launcher;

import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleStateProvider;

import java.util.Objects;

/**
 * Every module this build contains is ACTIVE; anything else is ABSENT.
 *
 * <p><strong>Development only (P7-7).</strong> Activation is not built until
 * P13, and the real {@code Entitlement} makes an unactivated installation
 * {@code UNLICENSED} - no reads, no writes. Until activation exists, a
 * development build runs on this so the application can be exercised with the
 * persistence guard and module registry genuinely in place.
 *
 * <p>It cannot reach a customer: {@link LicenceMode} refuses to start a release
 * build at all while this is the only provider available.
 */
final class DevelopmentEntitlement implements ModuleStateProvider {

    private final ModuleRegistry packaged;

    DevelopmentEntitlement(ModuleRegistry packaged) {
        this.packaged = Objects.requireNonNull(packaged, "packaged");
    }

    @Override
    public ModuleState moduleState(String moduleId) {
        ModuleId id = ModuleId.of(moduleId);
        if (id.isKernel() || packaged.isInstalled(id)) {
            return ModuleState.ACTIVE;
        }
        return ModuleState.ABSENT;
    }
}
