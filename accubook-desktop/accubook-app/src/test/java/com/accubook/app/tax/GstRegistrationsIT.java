package com.accubook.app.tax;

import com.accubook.app.company.CompanyService;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.RulePack;
import com.accubook.tax.RulePackException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GST registration")
class GstRegistrationsIT {

    /** Not a real taxpayer: the published illustrative GSTIN (check character V). */
    private static final String GSTIN = "27AAPFU0939F1ZV";

    @TempDir
    Path dir;

    private CompanyService service;
    private GstRegistrations registrations;
    private RulePack rules;

    @BeforeEach
    void setUp() throws Exception {
        service = CompanyService.start(dir);
        service.openCompany(service.createCompany("Asha Traders").id());
        registrations = new GstRegistrations(service.openCompanyTransactions(), Clock.systemUTC());
        rules = service.rules();
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    @Test
    @DisplayName("effective-dated: unregistered, then regular, then composition - each in force from its date")
    void history() {
        registrations.register(GstRegistration.Scheme.UNREGISTERED, null, "27", LocalDate.of(2026, 4, 1), rules);
        registrations.register(GstRegistration.Scheme.REGULAR, "27aapfu0939f1zv", "27", LocalDate.of(2026, 7, 1), rules);
        registrations.register(GstRegistration.Scheme.COMPOSITION, GSTIN, "27", LocalDate.of(2027, 4, 1), rules);

        assertThat(registrations.on(LocalDate.of(2026, 3, 31))).isEmpty();
        assertThat(registrations.on(LocalDate.of(2026, 6, 30)).orElseThrow().scheme())
                .isEqualTo(GstRegistration.Scheme.UNREGISTERED);
        assertThat(registrations.on(LocalDate.of(2026, 7, 1)).orElseThrow().gstin()).isEqualTo(GSTIN);
        assertThat(registrations.on(LocalDate.of(2027, 4, 1)).orElseThrow().rulePackVersion()).isEqualTo("2025.10.4");
    }

    @Test
    @DisplayName("a mistyped GSTIN, another state's GSTIN, or a GSTIN when unregistered is refused")
    void validation() {
        assertThatThrownBy(() -> registrations.register(GstRegistration.Scheme.REGULAR, "27AAPFU0939F1ZW", "27",
                LocalDate.of(2026, 4, 1), rules)).hasMessageContaining("check character");
        assertThatThrownBy(() -> registrations.register(GstRegistration.Scheme.REGULAR, GSTIN, "29",
                LocalDate.of(2026, 4, 1), rules)).hasMessageContaining("not 29");
        assertThatThrownBy(() -> registrations.register(GstRegistration.Scheme.UNREGISTERED, GSTIN, "27",
                LocalDate.of(2026, 4, 1), rules)).hasMessageContaining("no GSTIN");
        assertThatThrownBy(() -> registrations.register(GstRegistration.Scheme.UNREGISTERED, null, "55",
                LocalDate.of(2026, 4, 1), rules)).hasMessageContaining("not a state code");
    }

    @Test
    @DisplayName("forward only, and never edited: past documents keep their basis")
    void forwardOnly() {
        registrations.register(GstRegistration.Scheme.REGULAR, GSTIN, "27", LocalDate.of(2026, 7, 1), rules);
        assertThatThrownBy(() -> registrations.register(GstRegistration.Scheme.COMPOSITION, GSTIN, "27",
                LocalDate.of(2026, 6, 1), rules)).isInstanceOf(RulePackException.class).hasMessageContaining("after");
        assertThatThrownBy(() -> service.openCompanyTransactions().inTransaction(c -> {
            try (Statement s = c.createStatement()) {
                return s.executeUpdate("UPDATE gst_registration SET scheme = 'COMPOSITION'");
            }
        })).hasMessageContaining("never edited");
    }
}
