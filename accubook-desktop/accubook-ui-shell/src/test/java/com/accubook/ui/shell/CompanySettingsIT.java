package com.accubook.ui.shell;

import com.accubook.app.company.CompanyService;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.tax.CompanyIdentity;
import com.accubook.tax.GstRegistration;
import com.accubook.tax.GstRegistrations;
import com.accubook.tax.StateCode;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The company and GST screen against a real company file. */
@DisplayName("Company settings screen")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class CompanySettingsIT extends ApplicationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);

    private CompanyService service;
    private Label status;

    @Override
    public void start(Stage stage) throws Exception {
        Path dir = Files.createTempDirectory("accubook-settings");
        service = CompanyService.start(dir, ModuleRegistry.discover(List.of()), id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders", 4, LocalDate.of(2026, 4, 1)).id());
        status = new Label();
        status.setId("statusBar");
        ShellScreenContext context = new ShellScreenContext(service, CLOCK, ModuleState.ACTIVE,
                () -> LocalDate.of(2026, 4, 1), status::setText, (id, args) -> { });
        stage.setScene(new Scene(new BorderPane(new CompanySettingsScreen(context).view(), null, null, status, null),
                800, 760));
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    private void waitForText(String id, String prefix) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS,
                () -> lookup(id).queryAs(Label.class).getText().startsWith(prefix));
    }

    private void chooseState(String id, String code) {
        ComboBox<StateCode> box = lookup(id).query();
        interact(() -> box.setValue(box.getItems().stream().filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow()));
    }

    @Test
    @DisplayName("the address is saved and read back; a bad PIN is refused with the engine's reason")
    void identity() throws Exception {
        clickOn("#legalName").write("Asha Traders Private Limited").type(KeyCode.ENTER);
        write("12 MG Road").type(KeyCode.ENTER);
        write("Pune");
        chooseState("#state", "27");
        clickOn("#pin").write("041001").type(KeyCode.ENTER);
        waitForText("#statusBar", "Saving the company's address failed");
        assertThat(lookup("#statusBar").queryAs(Label.class).getText()).contains("not a PIN code");

        clickOn("#pin").eraseText(6).write("411001").type(KeyCode.ENTER);
        waitForText("#settingsMessage", "Company address saved.");

        CompanyIdentity.Address saved = new CompanyIdentity(service.openCompanyTransactions()).get().orElseThrow();
        assertThat(saved).isEqualTo(new CompanyIdentity.Address("Asha Traders Private Limited", "12 MG Road", "Pune",
                "27", "411001"));
    }

    @Test
    @DisplayName("a regular registration is recorded and listed; GSTIN and category follow the scheme")
    void registration() throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        ComboBox<GstRegistration.Scheme> scheme = lookup("#scheme").query();
        interact(() -> scheme.setValue(GstRegistration.Scheme.UNREGISTERED));
        assertThat(lookup("#gstin").query().isDisabled()).isTrue();
        assertThat(lookup("#compositionCategory").query().isDisabled()).isTrue();
        interact(() -> scheme.setValue(GstRegistration.Scheme.REGULAR));
        assertThat(lookup("#gstin").query().isDisabled()).isFalse();

        clickOn("#registrationFrom").write("1-4").type(KeyCode.ENTER);
        clickOn("#gstin").write("27PQRSX5678K1ZY");
        chooseState("#registrationState", "27");
        clickOn("#register");
        waitForText("#settingsMessage", "Registered regular from 01-04-2026.");

        TableView<GstRegistration> history = lookup("#registrationHistory").queryTableView();
        assertThat(history.getItems()).singleElement().satisfies(r -> {
            assertThat(r.gstin()).isEqualTo("27PQRSX5678K1ZY");
            assertThat(r.effectiveFrom()).isEqualTo(LocalDate.of(2026, 4, 1));
        });
        assertThat(new GstRegistrations(service.openCompanyTransactions(), CLOCK).on(LocalDate.of(2026, 5, 1)))
                .get().extracting(GstRegistration::scheme).isEqualTo(GstRegistration.Scheme.REGULAR);
    }

    @Test
    @DisplayName("a GSTIN from another state is refused, and nothing is recorded")
    void wrongState() throws Exception {
        clickOn("#registrationFrom").write("1-4").type(KeyCode.ENTER);
        clickOn("#gstin").write("27PQRSX5678K1ZY");
        chooseState("#registrationState", "29");
        clickOn("#register");
        waitForText("#statusBar", "Recording the GST registration failed");

        assertThat(new GstRegistrations(service.openCompanyTransactions(), CLOCK).history()).isEmpty();
        assertThat(lookup("#register").queryAs(Button.class).isDisabled()).isFalse();
    }
}
