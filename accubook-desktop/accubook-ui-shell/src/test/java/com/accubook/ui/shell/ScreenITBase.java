package com.accubook.ui.shell;

import com.accubook.app.company.CompanyService;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.ui.api.Screen;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * One kernel screen on a real display against a real company file (FY 2026-27,
 * 10 May 2026), with the status bar the main window would give it.
 */
abstract class ScreenITBase extends ApplicationTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-10T10:00:00Z"), ZoneOffset.UTC);

    CompanyService service;
    Label status;

    abstract Screen screen(ShellScreenContext context) throws Exception;

    /** Anything the screen should find when it opens. */
    void seed() throws Exception {
    }

    @Override
    public void start(Stage stage) throws Exception {
        service = CompanyService.start(Files.createTempDirectory("accubook-screen"), ModuleRegistry.discover(List.of()),
                id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders", 4, LocalDate.of(2026, 4, 1)).id());
        seed();
        status = new Label();
        status.setId("statusBar");
        ShellScreenContext context = new ShellScreenContext(service, CLOCK, ModuleState.ACTIVE,
                () -> LocalDate.of(2026, 4, 1), status::setText, (id, args) -> { });
        stage.setScene(new Scene(new BorderPane(screen(context).view(), null, null, status, null), 1000, 760));
        stage.show();
    }

    @AfterEach
    void closeService() throws Exception {
        service.close();
    }

    void waitFor(Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, condition);
        WaitForAsyncUtils.waitForFxEvents();
    }

    void waitForText(String id, String prefix) throws Exception {
        waitFor(() -> lookup(id).queryAs(Label.class).getText().startsWith(prefix));
    }
}
