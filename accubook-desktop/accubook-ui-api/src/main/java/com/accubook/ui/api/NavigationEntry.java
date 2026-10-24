package com.accubook.ui.api;

import javafx.scene.input.KeyCombination;

import java.util.Objects;
import java.util.function.Function;

/**
 * A screen a module offers: where it sits, what opens it, and how to build it.
 *
 * @param id       unique across the application; used for cross-links
 * @param shortcut a key that opens it from anywhere (S-1), or null
 */
public record NavigationEntry(String id, Section section, String title, int order, KeyCombination shortcut,
                              Function<ScreenContext, Screen> factory) {

    public NavigationEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(factory, "factory");
    }

    public static NavigationEntry of(String id, Section section, String title, int order,
                                     Function<ScreenContext, Screen> factory) {
        return new NavigationEntry(id, section, title, order, null, factory);
    }

    public NavigationEntry withShortcut(KeyCombination key) {
        return new NavigationEntry(id, section, title, order, key, factory);
    }
}
