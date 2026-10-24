package com.accubook.ui.shell;

import com.accubook.module.ModuleId;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleStateProvider;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.Section;
import javafx.scene.input.KeyCombination;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The screens this installation shows, from the providers present and each
 * module's state. ACTIVE and READ_ONLY modules show their screens - READ_ONLY
 * with editing disabled; DORMANT and ABSENT ones show none (a dormant module's
 * records are reached through the records viewer). No plan is named anywhere.
 */
final class ScreenCatalogue {

    /** A screen that can be opened, and whether it may edit. */
    record Available(NavigationEntry entry, ModuleId module, ModuleState state) {

        boolean readOnly() {
            return !state.allowsMutation();
        }
    }

    private final List<Available> screens;

    private ScreenCatalogue(List<Available> screens) {
        this.screens = List.copyOf(screens);
    }

    static ScreenCatalogue of(List<ScreenProvider> providers, ModuleStateProvider states) {
        List<Available> screens = new ArrayList<>();
        Map<String, ModuleId> ids = new HashMap<>();
        Map<KeyCombination, String> keys = new HashMap<>();
        for (ScreenProvider provider : providers) {
            ModuleState state = states.moduleState(provider.module().value());
            if (state != ModuleState.ACTIVE && state != ModuleState.READ_ONLY) {
                continue;
            }
            for (NavigationEntry entry : provider.entries()) {
                ModuleId earlier = ids.putIfAbsent(entry.id(), provider.module());
                if (earlier != null) {
                    throw new IllegalStateException("Screen '" + entry.id() + "' is offered by both " + earlier
                            + " and " + provider.module());
                }
                if (entry.shortcut() != null) {
                    String taken = keys.putIfAbsent(entry.shortcut(), entry.id());
                    if (taken != null) {
                        throw new IllegalStateException(entry.shortcut().getDisplayText() + " opens both '" + taken
                                + "' and '" + entry.id() + "'");
                    }
                }
                screens.add(new Available(entry, provider.module(), state));
            }
        }
        screens.sort(Comparator.comparing((Available a) -> a.entry().section())
                .thenComparingInt(a -> a.entry().order()).thenComparing(a -> a.entry().title()));
        return new ScreenCatalogue(screens);
    }

    /** The sections that have at least one screen, in navigation order. */
    List<Section> sections() {
        return Arrays.stream(Section.values()).filter(s -> !in(s).isEmpty()).toList();
    }

    List<Available> in(Section section) {
        return screens.stream().filter(a -> a.entry().section() == section).toList();
    }

    Optional<Available> find(String id) {
        return screens.stream().filter(a -> a.entry().id().equals(id)).findFirst();
    }

    List<Available> all() {
        return screens;
    }
}
