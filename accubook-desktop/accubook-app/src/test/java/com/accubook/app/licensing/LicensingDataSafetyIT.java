package com.accubook.app.licensing;

import com.accubook.app.company.Company;
import com.accubook.app.company.CompanyService;
import com.accubook.licensing.Entitlement;
import com.accubook.licensing.EntitlementLoader;
import com.accubook.licensing.machine.MachineFingerprint;
import com.accubook.licensing.state.SubscriptionState;
import com.accubook.licensing.time.TimeAnchor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security requirement: <strong>rejecting an entitlement must never delete
 * or modify a company database.</strong>
 *
 * <p>A licensing failure is a commercial problem. Treating it as a reason to
 * withhold or damage a customer's statutory records would be indefensible, and
 * it is also what makes people crack software rather than renew.
 *
 * <p>Each test hashes the company file before and after a licensing failure and
 * asserts the bytes are identical - not merely that the file still exists.
 */
@DisplayName("Licensing failure and customer data")
class LicensingDataSafetyIT {

    private static final Set<String> PACKAGED = Set.of("accounting");
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** SHA-256 of the file, so "unchanged" means byte-identical. */
    private static String hashOf(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    private record Books(Path companyFile, String hash, String companyId) {
    }

    /** Create a company with real accounting data and record its exact bytes. */
    private Books createBooks(Path dir) throws Exception {
        String companyId;
        try (CompanyService service = CompanyService.start(dir)) {
            Company company = service.createCompany("Alpha Traders");
            companyId = company.id();
            service.openCompany(companyId);
        }
        Path file = dir.resolve("companies").resolve(companyId).resolve("company.db");
        return new Books(file, hashOf(file), companyId);
    }

    @Test
    @DisplayName("a missing entitlement leaves company.db byte-identical")
    void missingEntitlementDoesNotTouchBooks(@TempDir Path dir) throws Exception {
        Books books = createBooks(dir);

        EntitlementLoader loader = EntitlementLoader.forThisBuild(PACKAGED, ZONE);
        Entitlement e = loader.load(dir.resolve("no-such-file"),
                MachineFingerprint.of(List.of("a", "b", "c", "d", "e")),
                new TimeAnchor(Instant.now()), Instant.now());

        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.UNLICENSED);
        assertThat(Files.exists(books.companyFile())).isTrue();
        assertThat(hashOf(books.companyFile())).isEqualTo(books.hash());
    }

    @Test
    @DisplayName("a corrupt entitlement leaves company.db byte-identical")
    void corruptEntitlementDoesNotTouchBooks(@TempDir Path dir) throws Exception {
        Books books = createBooks(dir);

        Path entitlement = dir.resolve(EntitlementLoader.ENTITLEMENT_FILE);
        Files.writeString(entitlement, "garbage", StandardCharsets.UTF_8);

        EntitlementLoader loader = EntitlementLoader.forThisBuild(PACKAGED, ZONE);
        Entitlement e = loader.load(entitlement,
                MachineFingerprint.of(List.of("a", "b", "c", "d", "e")),
                new TimeAnchor(Instant.now()), Instant.now());

        assertThat(e.subscriptionState()).isEqualTo(SubscriptionState.INVALID);
        assertThat(hashOf(books.companyFile())).isEqualTo(books.hash());
    }

    @Test
    @DisplayName("an entitlement bound to another machine leaves company.db byte-identical")
    void wrongMachineDoesNotTouchBooks(@TempDir Path dir) throws Exception {
        Books books = createBooks(dir);

        Path entitlement = dir.resolve(EntitlementLoader.ENTITLEMENT_FILE);
        Files.writeString(entitlement, "some-key\nAAAA\nv=1\n", StandardCharsets.UTF_8);

        EntitlementLoader loader = EntitlementLoader.forThisBuild(PACKAGED, ZONE);
        loader.load(entitlement, MachineFingerprint.of(List.of("z", "z", "z", "z", "z")),
                new TimeAnchor(Instant.now()), Instant.now());

        assertThat(hashOf(books.companyFile())).isEqualTo(books.hash());
    }

    @Test
    @DisplayName("the books remain openable and readable after a licensing failure")
    void booksRemainReadableAfterFailure(@TempDir Path dir) throws Exception {
        Books books = createBooks(dir);

        Path entitlement = dir.resolve(EntitlementLoader.ENTITLEMENT_FILE);
        Files.writeString(entitlement, "garbage", StandardCharsets.UTF_8);
        EntitlementLoader.forThisBuild(PACKAGED, ZONE)
                .load(entitlement, MachineFingerprint.of(List.of("a", "b", "c", "d", "e")),
                        new TimeAnchor(Instant.now()), Instant.now());

        // The whole point of read-only rather than locked-out: the customer can
        // still open their books and get their data out.
        try (CompanyService service = CompanyService.start(dir)) {
            service.openCompany(books.companyId());
            assertThat(service.chartOfAccounts()).isNotEmpty();
            assertThat(service.trialBalanceTotals().balanced()).isTrue();
        }
    }

    @Test
    @DisplayName("no licensing state permits mutation while forbidding read")
    void readIsNeverWithheldWhileWritingIsAllowed(@TempDir Path dir) {
        // Reading is only withheld before activation, when there is nothing to
        // read anyway. Every other state keeps history available.
        for (SubscriptionState state : SubscriptionState.values()) {
            if (state == SubscriptionState.UNLICENSED) {
                continue;
            }
            assertThat(state.allowsRead())
                    .as("%s must permit reading and exporting", state)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the entitlement file lives outside the company directories")
    void entitlementIsNotStoredWithBooks(@TempDir Path dir) throws Exception {
        Books books = createBooks(dir);
        Path entitlement = dir.resolve(EntitlementLoader.ENTITLEMENT_FILE);
        Files.writeString(entitlement, "anything");

        // Installation state and customer books are separate, so restoring a
        // company file cannot roll back a subscription, and copying one to
        // another machine cannot carry an activation with it.
        assertThat(entitlement.getParent()).isNotEqualTo(books.companyFile().getParent());
        assertThat(books.companyFile().toString()).contains("companies");
    }
}
