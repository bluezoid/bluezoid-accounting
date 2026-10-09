package com.accubook.inventory;

import com.accubook.app.company.CompanyService;
import com.accubook.licensing.Entitlement;
import com.accubook.licensing.token.EntitlementToken;
import com.accubook.module.EntitlementGuard;
import com.accubook.module.ModuleNotWritableException;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.module.ModuleStateProvider;
import com.accubook.persistence.guard.UnauthorizedWriteException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two tests carried from P4, possible now that a real optional module
 * exists.
 */
@DisplayName("Inventory across plan changes")
class InventoryPlanChangeIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    /** A real entitlement for a plan whose licence does not include inventory. */
    private static Entitlement basicLicence(Set<String> packaged) {
        EntitlementToken token = new EntitlementToken("lic-1", "cust-1", "BASIC",
                Set.of("sales-invoicing", "purchase-billing", "reporting-extended"),
                TODAY.minusMonths(1), TODAY.plusMonths(11), EntitlementToken.DEFAULT_RENEWAL_WINDOW_DAYS,
                List.of("a", "b", "c", "d", "e"), false, Instant.parse("2026-09-09T00:00:00Z"), "tok-1");
        return Entitlement.resolve(token, packaged, TODAY);
    }

    @Test
    @DisplayName("P4 carried: a smuggled inventory module resolves READ_ONLY and is refused at both layers")
    void smuggledModuleIsReadOnly(@TempDir Path dir) throws Exception {
        // The inventory jar is on the module path - ServiceLoader finds it - but
        // the licence is BASIC. The licence, not the module path, decides.
        ModuleRegistry discovered = ModuleRegistry.discover(List.of(new InventoryModule()));
        Entitlement licence = basicLicence(Set.of("inventory"));
        assertThat(licence.moduleState("inventory")).isEqualTo(ModuleState.READ_ONLY);

        try (CompanyService service = CompanyService.start(dir, discovered, licence)) {
            service.openCompany(service.createCompany("Asha Traders").id());
            Inventory inventory = new Inventory(service.openCompanyTransactions(),
                    new EntitlementGuard(service.moduleStates()));

            assertThatThrownBy(() -> inventory.createUnit("NOS", "Numbers", 0))
                    .isInstanceOf(ModuleNotWritableException.class);
            // And a write that never asks the use case - the reflection route
            // the P4 review measured - is refused by the guard.
            assertThatThrownBy(() -> service.openCompanyTransactions().inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("INSERT INTO unit (id, symbol, name, decimals, created_at, "
                            + "updated_at) VALUES ('u', 'NOS', 'Numbers', 0, 0, 0)");
                }
            })).isInstanceOf(UnauthorizedWriteException.class);
        }
    }

    @Test
    @DisplayName("P4 carried: PRO -> BASIC -> PRO keeps inventory history byte-for-byte and writable again")
    void downgradeAndBack(@TempDir Path dir) throws Exception {
        // PRO: inventory installed and active; record some history.
        String companyId;
        String riceId;
        String mainId;
        ModuleStateProvider pro = id -> ModuleState.ACTIVE;
        try (CompanyService service = CompanyService.start(dir,
                ModuleRegistry.discover(List.of(new InventoryModule())), pro)) {
            companyId = service.createCompany("Asha Traders").id();
            service.openCompany(companyId);
            Inventory inventory = new Inventory(service.openCompanyTransactions(), new EntitlementGuard(pro));
            Unit kg = inventory.createUnit("KG", "Kilograms", 3);
            riceId = inventory.createItem("Rice", ItemKind.GOODS, kg.id(), null, null, null, null).id();
            mainId = inventory.createWarehouse("Main", null).id();
            inventory.record(MovementRequest.in(MovementType.PURCHASE, riceId, mainId,
                    new BigDecimal("40"), new BigDecimal("80"), TODAY, null));
            inventory.record(MovementRequest.out(MovementType.SALE, riceId, mainId,
                    new BigDecimal("15"), TODAY, null));
        }
        Path file = dir.resolve("companies").resolve(companyId).resolve("company.db");
        String before = sha256(file);

        // BASIC: a build without the inventory module. Its tables are DORMANT.
        ModuleStateProvider basic = id -> id.equals("inventory") ? ModuleState.DORMANT : ModuleState.ACTIVE;
        try (CompanyService service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), basic)) {
            service.openCompany(companyId);

            long movements = service.readOpenCompany(connection -> {
                try (Statement s = connection.createStatement();
                     var rows = s.executeQuery("SELECT COUNT(*) FROM stock_movement")) {
                    rows.next();
                    return rows.getLong(1);
                }
            });
            assertThat(movements).as("history readable without the module").isEqualTo(2);

            assertThatThrownBy(() -> service.openCompanyTransactions().inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("UPDATE stock SET quantity_e4 = 0");
                }
            })).as("no inventory code in this build, and still no way to write its tables")
                    .isInstanceOf(UnauthorizedWriteException.class);
        }
        assertThat(sha256(file)).as("opening in the smaller build changed nothing").isEqualTo(before);

        // PRO again: everything is where it was, and writable.
        try (CompanyService service = CompanyService.start(dir,
                ModuleRegistry.discover(List.of(new InventoryModule())), pro)) {
            service.openCompany(companyId);
            Inventory inventory = new Inventory(service.openCompanyTransactions(), new EntitlementGuard(pro));

            assertThat(inventory.stockOf(riceId, mainId)).isEqualByComparingTo("25");
            assertThat(inventory.verifyStock()).isEmpty();
            inventory.record(MovementRequest.out(MovementType.SALE, riceId, mainId,
                    new BigDecimal("5"), TODAY, null));
            assertThat(inventory.stockOf(riceId, mainId)).isEqualByComparingTo("20");
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
