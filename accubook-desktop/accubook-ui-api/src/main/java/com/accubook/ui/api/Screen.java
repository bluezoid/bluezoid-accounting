package com.accubook.ui.api;

import javafx.scene.Node;

/** One screen in a tab of the main window. */
public interface Screen {

    /** The screen's content, built once. */
    Node view();

    /** Called each time its tab is shown; a list refreshes here. */
    default void shown() {
    }

    /**
     * Whether the tab may close now. A screen with unsaved entry asks the
     * person first; the default has nothing to lose.
     */
    default boolean canClose() {
        return true;
    }
}
