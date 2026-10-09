package com.accubook.persistence.paths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Where the application keeps its data on each operating system.
 *
 * <p>The layout implements decision B-6: one {@code app.db} for installation
 * state and one {@code company.db} per company, each in its own directory.
 *
 * <pre>
 * &lt;root&gt;/
 *   app.db                       installation state - NOT customer books
 *   companies/&lt;uuid&gt;/company.db  the books, one company only
 *   companies/&lt;uuid&gt;/attachments/
 *   backups/  logs/  tmp/
 * </pre>
 *
 * <p><strong>This is deliberately outside the installation directory.</strong>
 * An installer or uninstaller that touched customer books would be a data-loss
 * bug, so program files and data never share a root.
 */
public final class AppPaths {

    private static final String APP_DIR_NAME = "AccuBook";
    private static final String APP_DIR_NAME_POSIX = "accubook";

    private final Path root;

    AppPaths(Path root) {
        this.root = root;
    }

    /** Resolve the platform-appropriate data root. */
    public static AppPaths forCurrentPlatform() {
        return new AppPaths(defaultRoot());
    }

    /**
     * Use an explicit root. Supports the {@code --data-dir} override for
     * customers who keep data on a second drive, and gives tests an isolated
     * directory.
     */
    public static AppPaths at(Path root) {
        return new AppPaths(root);
    }

    static Path defaultRoot() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            Path base = (localAppData != null && !localAppData.isBlank())
                    ? Paths.get(localAppData)
                    : Paths.get(home, "AppData", "Local");
            return base.resolve(APP_DIR_NAME);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Paths.get(home, "Library", "Application Support", APP_DIR_NAME);
        }
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path base = (xdgDataHome != null && !xdgDataHome.isBlank())
                ? Paths.get(xdgDataHome)
                : Paths.get(home, ".local", "share");
        return base.resolve(APP_DIR_NAME_POSIX);
    }

    public Path root() {
        return root;
    }

    /** Installation state. Never customer accounting data. */
    public Path appDatabase() {
        return root.resolve("app.db");
    }

    public Path companiesDir() {
        return root.resolve("companies");
    }

    /** Directory holding one company's database and its attachments. */
    public Path companyDir(String companyId) {
        return companiesDir().resolve(companyId);
    }

    /** One company's books. */
    public Path companyDatabase(String companyId) {
        return companyDir(companyId).resolve("company.db");
    }

    public Path companyAttachmentsDir(String companyId) {
        return companyDir(companyId).resolve("attachments");
    }

    public Path backupsDir() {
        return root.resolve("backups");
    }

    public Path logsDir() {
        return root.resolve("logs");
    }

    /**
     * Staging area for restores and migrations.
     *
     * <p>Deliberately on the same volume as the databases, so a completed
     * restore can be moved into place atomically rather than copied across a
     * filesystem boundary.
     */
    public Path tmpDir() {
        return root.resolve("tmp");
    }

    /** Create the directory skeleton, owner-only where the OS supports it. */
    public void createDirectories() throws IOException {
        createSecureDirectory(root);
        createSecureDirectory(companiesDir());
        createSecureDirectory(backupsDir());
        createSecureDirectory(logsDir());
        createSecureDirectory(tmpDir());
    }

    /** Create a company's directory and its attachments subdirectory. */
    public void createCompanyDirectories(String companyId) throws IOException {
        createSecureDirectory(companyDir(companyId));
        createSecureDirectory(companyAttachmentsDir(companyId));
    }

    /**
     * Create a directory readable only by its owner where POSIX permissions
     * exist. Accounting data should not be world-readable on a shared machine.
     */
    static void createSecureDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            return;
        }
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            // Windows: ACL inheritance from %LOCALAPPDATA% already restricts to
            // the user. Nothing to do, and not an error.
        }
    }
}
