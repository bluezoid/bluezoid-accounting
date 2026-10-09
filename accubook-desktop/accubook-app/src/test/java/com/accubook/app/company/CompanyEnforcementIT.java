package com.accubook.app.company;

import com.accubook.module.FeatureModule;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.persistence.guard.UnauthorizedWriteException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persistence guard in the running application, not just in a unit test:
 * every company {@link CompanyService} opens has it installed.
 */
@DisplayName("Entitlement enforcement through CompanyService")
class CompanyEnforcementIT {

    /** Stands in for a feature module; claims a real table for the test. */
    private static final FeatureModule NOTES = new FeatureModule() {
        @Override
        public ModuleId id() {
            return ModuleId.of("notes");
        }

        @Override
        public String displayName() {
            return "Notes";
        }

        @Override
        public Set<String> ownedTables() {
            return Set.of("number_counter");
        }
    };

    private static int writeOwnedTable(CompanyService service) throws Exception {
        return service.openCompanyTransactions().inTransaction(connection -> {
            try (Statement s = connection.createStatement()) {
                return s.executeUpdate(
                        "INSERT INTO number_counter (scope, last_number, updated_at) VALUES ('t', 0, 0)");
            }
        });
    }

    @Test
    @DisplayName("a READ_ONLY module's table refuses writes in an opened company")
    void readOnlyRefused(@TempDir Path dir) throws Exception {
        Map<String, ModuleState> states = new HashMap<>(Map.of("notes", ModuleState.READ_ONLY));
        try (CompanyService service = CompanyService.start(dir,
                ModuleRegistry.discover(List.of(NOTES)), id -> states.getOrDefault(id, ModuleState.ACTIVE))) {
            service.openCompany(service.createCompany("Alpha Traders").id());

            assertThatThrownBy(() -> writeOwnedTable(service))
                    .isInstanceOf(UnauthorizedWriteException.class)
                    .hasMessageContaining("notes");

            // Entitlement restored: the same write goes through.
            states.put("notes", ModuleState.ACTIVE);
            assertThat(writeOwnedTable(service)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("kernel tables stay writable whatever a feature module's state")
    void kernelUnaffected(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir,
                ModuleRegistry.discover(List.of(NOTES)), id -> ModuleState.READ_ONLY)) {
            service.openCompany(service.createCompany("Alpha Traders").id());

            int updated = service.openCompanyTransactions().inTransaction(connection -> {
                try (Statement s = connection.createStatement()) {
                    return s.executeUpdate("UPDATE company_info SET legal_name = 'Alpha Traders Pvt Ltd'");
                }
            });
            assertThat(updated).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the guard is installed again when another company is opened")
    void installedOnEveryOpen(@TempDir Path dir) throws Exception {
        try (CompanyService service = CompanyService.start(dir,
                ModuleRegistry.discover(List.of(NOTES)), id -> ModuleState.READ_ONLY)) {
            Company first = service.createCompany("Alpha Traders");
            Company second = service.createCompany("Beta Stores");
            service.openCompany(first.id());
            service.openCompany(second.id());

            assertThatThrownBy(() -> writeOwnedTable(service))
                    .isInstanceOf(UnauthorizedWriteException.class);
        }
    }
}
