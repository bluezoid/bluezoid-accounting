package com.accubook.launcher;

import com.accubook.app.company.CompanyService;
import com.accubook.ui.shell.ShellApplication;
import javafx.application.Application;

import java.nio.file.Path;

/**
 * Entry point and composition root.
 *
 * <p>Separate from {@link ShellApplication} because a JavaFX {@code Application}
 * subclass used directly as a main class needs the JavaFX modules on the module
 * path at launch. A plain class delegating to {@code Application.launch} keeps
 * the packaged launcher working in a jlink image, which is what jpackage
 * produces.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Path dataRoot = resolveDataRoot(args);
        ShellApplication.setServiceFactory(() -> dataRoot == null
                ? CompanyService.startAtDefaultLocation()
                : CompanyService.start(dataRoot));
        Application.launch(ShellApplication.class, args);
    }

    /**
     * Resolve an explicit data directory from {@code --data-dir=<path>}, or
     * {@code null} to use the platform default.
     *
     * <p>The override exists for customers who keep data on a second drive, and
     * it is what lets the packaging smoke test run against a throwaway
     * directory instead of the real one.
     */
    static Path resolveDataRoot(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--data-dir=")) {
                return Path.of(arg.substring("--data-dir=".length()));
            }
        }
        return null;
    }
}
