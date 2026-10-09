package com.accubook.launcher;

import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleStateProvider;

/**
 * Chooses where module states come from, by build channel.
 *
 * <p>The channel is baked into the launcher by {@code build.sh}: a release
 * build passes {@code -Daccubook.channel=release}. Anything else is a
 * development build.
 *
 * <h2>The release gate (P7-7)</h2>
 * A release build <strong>refuses to start</strong> until licence activation
 * is implemented. That is deliberate and structural: the alternative - a check
 * that greps for the development provider - can be forgotten, whereas a release
 * build that will not launch cannot be shipped by accident.
 */
final class LicenceMode {

    static final String CHANNEL_PROPERTY = "accubook.channel";
    static final String RELEASE = "release";

    private LicenceMode() {
    }

    /**
     * @throws IllegalStateException on a release build, which must not run on the
     *                               development provider
     */
    static ModuleStateProvider select(String channel, ModuleRegistry packaged) {
        if (RELEASE.equals(channel)) {
            throw new IllegalStateException(
                    "This is a release build, and licence activation is not implemented yet. "
                            + "A release build must not run on the development entitlement (P7-7).");
        }
        return new DevelopmentEntitlement(packaged);
    }
}
