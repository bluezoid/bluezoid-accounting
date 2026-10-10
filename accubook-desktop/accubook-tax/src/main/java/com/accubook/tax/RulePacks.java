package com.accubook.tax;

import com.accubook.persistence.connection.Database;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The rule packs installed on this machine, in {@code app.db} (P9-4).
 *
 * <p>Installation state, not company data: a posted document stores the tax it
 * resolved and the pack version used, so the company file never needs a pack to
 * read its own history (DATABASE_ARCHITECTURE §2).
 *
 * <p>Every load re-verifies the stored file's signature. The table is
 * append-only, but a hand-edited {@code app.db} must not be able to change a tax
 * rate silently.
 */
public final class RulePacks {

    private static final String BUNDLED = "/com/accubook/tax/bundled.rulepack";

    private final Database appDatabase;
    private final Map<String, String> trustedKeys;
    private final java.time.Clock clock;

    public RulePacks(Database appDatabase, Map<String, String> trustedKeys, java.time.Clock clock) {
        this.appDatabase = Objects.requireNonNull(appDatabase, "appDatabase");
        this.trustedKeys = Map.copyOf(trustedKeys);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Over this build's trusted keys. */
    public static RulePacks forThisBuild(Database appDatabase) {
        return new RulePacks(appDatabase, TrustedRulePackKeys.forThisBuild(), java.time.Clock.systemUTC());
    }

    /** The pack shipped inside this build. */
    public static SignedRulePack.Verified bundled(Map<String, String> trustedKeys) {
        try (InputStream in = RulePacks.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                throw new RulePackException("This build has no bundled rule pack");
            }
            return SignedRulePack.verify(new String(in.readAllBytes(), StandardCharsets.UTF_8), trustedKeys);
        } catch (IOException e) {
            throw new RulePackException("The bundled rule pack could not be read", e);
        }
    }

    /** Install the bundled pack if nothing newer is installed. Run at startup. */
    public void installBundled() {
        SignedRulePack.Verified bundled = bundled(trustedKeys);
        Optional<String> current = activeVersion();
        if (current.isEmpty() || RulePack.compareVersions(bundled.pack().version(), current.get()) > 0) {
            insert(bundled, "BUNDLED");
        }
    }

    /**
     * Install a newer pack from a file (offline import, P9-4). Refuses anything
     * unsigned, untrusted, altered, or not newer than what is installed - a pack
     * cannot be used to roll rates back.
     */
    public RulePack importFile(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RulePackException("The rule pack file could not be read: " + e.getMessage(), e);
        }
        SignedRulePack.Verified verified = SignedRulePack.verify(text, trustedKeys);
        Optional<String> current = activeVersion();
        if (current.isPresent() && RulePack.compareVersions(verified.pack().version(), current.get()) <= 0) {
            throw new RulePackException("Rule pack " + verified.pack().version() + " is not newer than the installed "
                    + current.get());
        }
        insert(verified, "IMPORTED");
        return verified.pack();
    }

    /** The newest installed pack, verified again as it is loaded. */
    public RulePack active() {
        try (Connection connection = appDatabase.openReadConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT version, file FROM rule_pack ORDER BY installed_order DESC")) {
            String bestVersion = null;
            String bestFile = null;
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    if (bestVersion == null || RulePack.compareVersions(rows.getString(1), bestVersion) > 0) {
                        bestVersion = rows.getString(1);
                        bestFile = rows.getString(2);
                    }
                }
            }
            if (bestFile == null) {
                throw new RulePackException("No rule pack is installed");
            }
            try {
                return SignedRulePack.verify(bestFile, trustedKeys).pack();
            } catch (RulePackException e) {
                throw new RulePackException("The installed rule pack " + bestVersion + " failed verification. "
                        + "app.db may have been altered; re-import a signed pack.", e);
            }
        } catch (SQLException e) {
            throw new RulePackException("Could not read installed rule packs: " + e.getMessage(), e);
        }
    }

    public record Installed(String version, LocalDate issued, String status, String source) {
    }

    public List<Installed> installed() {
        List<Installed> result = new ArrayList<>();
        try (Connection connection = appDatabase.openReadConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT version, issued, status, source FROM rule_pack ORDER BY installed_order");
             ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                result.add(new Installed(rows.getString(1), LocalDate.parse(rows.getString(2)), rows.getString(3),
                        rows.getString(4)));
            }
        } catch (SQLException e) {
            throw new RulePackException("Could not read installed rule packs: " + e.getMessage(), e);
        }
        return result;
    }

    private Optional<String> activeVersion() {
        List<Installed> all = installed();
        return all.stream().map(Installed::version).max(RulePack::compareVersions);
    }

    private void insert(SignedRulePack.Verified verified, String source) {
        try {
            appDatabase.inTransaction(connection -> {
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO rule_pack (version, issued, status, "
                        + "file, key_id, source, installed_at, installed_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, (SELECT COALESCE(MAX(installed_order), 0) + 1 FROM rule_pack))")) {
                    ps.setString(1, verified.pack().version());
                    ps.setString(2, verified.pack().issued().toString());
                    ps.setString(3, verified.pack().status());
                    ps.setString(4, verified.file());
                    ps.setString(5, verified.keyId());
                    ps.setString(6, source);
                    ps.setLong(7, clock.millis());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            throw new RulePackException("Could not install rule pack " + verified.pack().version() + ": "
                    + e.getMessage(), e);
        }
    }
}
