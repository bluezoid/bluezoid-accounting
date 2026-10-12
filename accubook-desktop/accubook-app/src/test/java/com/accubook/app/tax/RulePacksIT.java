package com.accubook.app.tax;

import com.accubook.app.company.CompanyService;
import com.accubook.app.schema.Schemas;
import com.accubook.persistence.connection.Database;
import com.accubook.persistence.migration.MigrationRunner;
import com.accubook.tax.RulePackException;
import com.accubook.tax.RulePacks;
import com.accubook.tax.SignedRulePack;
import com.accubook.tax.TrustedRulePackKeys;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Statement;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Installing and importing rule packs into a real app.db. */
@DisplayName("Rule packs")
class RulePacksIT {

    @TempDir
    Path dir;

    private Database appDb;
    private KeyPair testKeys;
    private RulePacks packs;

    @BeforeEach
    void setUp() throws Exception {
        appDb = Database.open(dir.resolve("app.db"));
        new MigrationRunner(Schemas.appDatabase(), dir.resolve("backups")).migrate(appDb);
        testKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Map<String, String> trusted = new HashMap<>(TrustedRulePackKeys.forThisBuild());
        trusted.put("test-key", Base64.getEncoder().encodeToString(testKeys.getPublic().getEncoded()));
        packs = new RulePacks(appDb, trusted, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() throws Exception {
        appDb.close();
    }

    private Path signedPack(String version) throws Exception {
        String body = RulePacks.bundled(TrustedRulePackKeys.forThisBuild()).body()
                .replace("version=" + RulePacks.bundledPack().version(), "version=" + version);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(testKeys.getPrivate());
        signer.update(body.getBytes(StandardCharsets.UTF_8));
        Path file = dir.resolve("pack-" + version + ".rulepack");
        Files.writeString(file, SignedRulePack.HEADER + "\nkey=test-key\nsignature="
                + Base64.getEncoder().encodeToString(signer.sign()) + "\n---\n" + body);
        return file;
    }

    @Test
    @DisplayName("the bundled pack installs once, and is the active pack")
    void bundledInstallsOnce() {
        packs.installBundled();
        packs.installBundled();

        assertThat(packs.installed()).singleElement().satisfies(i -> assertThat(i.source()).isEqualTo("BUNDLED"));
        assertThat(packs.active().version()).isEqualTo("2025.10.2");
    }

    @Test
    @DisplayName("a newer signed pack imports and takes over; an older or equal one is refused (no rollback)")
    void importNewer() throws Exception {
        packs.installBundled();

        assertThat(packs.importFile(signedPack("2026.4.1")).version()).isEqualTo("2026.4.1");
        assertThat(packs.active().version()).isEqualTo("2026.4.1");
        assertThatThrownBy(() -> packs.importFile(signedPack("2026.4.1"))).hasMessageContaining("not newer");
        assertThatThrownBy(() -> packs.importFile(signedPack("2025.11"))).hasMessageContaining("not newer");

        packs.installBundled();
        assertThat(packs.active().version()).as("a restart does not roll back to the bundled pack")
                .isEqualTo("2026.4.1");
    }

    @Test
    @DisplayName("an altered pack file is refused on import")
    void alteredFileRefused() throws Exception {
        Path file = signedPack("2026.4.1");
        Files.writeString(file, Files.readString(file).replace("|10|20|MONTHLY|50000|", "|5|20|MONTHLY|50000|"));
        assertThatThrownBy(() -> packs.importFile(file)).isInstanceOf(RulePackException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("installed packs cannot be changed or removed; a hand-edited app.db fails verification on load")
    void tamperedAppDb() throws Exception {
        packs.installBundled();
        assertThatThrownBy(() -> appDb.inUnguardedTransaction(c -> {
            try (Statement s = c.createStatement()) {
                return s.executeUpdate("UPDATE rule_pack SET status = 'VERIFIED'");
            }
        })).hasMessageContaining("cannot be changed");

        appDb.inUnguardedTransaction(c -> {
            try (Statement s = c.createStatement()) {
                s.execute("DROP TRIGGER rule_pack_no_update");
                return s.executeUpdate("UPDATE rule_pack SET file = replace(file, '|10|20|MONTHLY|', '|1|20|MONTHLY|')");
            }
        });
        assertThatThrownBy(() -> packs.active()).hasMessageContaining("failed verification");
    }

    @Test
    @DisplayName("CompanyService installs the bundled pack at start and serves its rules")
    void throughCompanyService() throws Exception {
        try (CompanyService service = CompanyService.start(dir.resolve("installation"))) {
            assertThat(service.rules().version()).isEqualTo("2025.10.2");
            assertThat(service.installedRulePacks()).hasSize(1);
        }
    }
}
