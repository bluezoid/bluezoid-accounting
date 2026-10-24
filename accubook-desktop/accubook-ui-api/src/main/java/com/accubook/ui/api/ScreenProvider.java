package com.accubook.ui.api;

import com.accubook.module.ModuleId;

import java.util.List;

/**
 * The screens of one module, found by the shell through {@code ServiceLoader}.
 * The shell never requires a feature: a build without the module has neither
 * its engine nor this provider, and shows nothing for it.
 */
public interface ScreenProvider {

    /** The module whose state decides whether these screens may edit. */
    ModuleId module();

    List<NavigationEntry> entries();
}
