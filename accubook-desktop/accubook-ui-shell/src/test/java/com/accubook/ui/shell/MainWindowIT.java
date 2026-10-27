package com.accubook.ui.shell;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.app.company.CompanyService;
import com.accubook.module.ModuleId;
import com.accubook.module.ModuleRegistry;
import com.accubook.module.ModuleState;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.Section;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Files;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The main window with stand-in modules: read-only banners, shortcuts, and a screen that refuses to close. */
@DisplayName("Main window")
@EnabledIfSystemProperty(named = "accubook.ui.tests", matches = "true")
class MainWindowIT extends ApplicationTest {

    private CompanyService service;
    private final AtomicReference<ModuleState> seenState = new AtomicReference<>();
    private boolean closable;

    private ScreenProvider provider(String module, NavigationEntry entry) {
        return new ScreenProvider() {
            @Override
            public ModuleId module() {
                return ModuleId.of(module);
            }

            @Override
            public List<NavigationEntry> entries() {
                return List.of(entry);
            }
        };
    }

    private Screen screen(String text, com.accubook.ui.api.ScreenContext context) {
        seenState.set(context.state());
        Label label = new Label(text);
        return new Screen() {
            @Override
            public javafx.scene.Node view() {
                return label;
            }

            @Override
            public boolean canClose() {
                return closable;
            }
        };
    }

    @Override
    public void start(Stage stage) throws Exception {
        service = CompanyService.start(Files.createTempDirectory("accubook-main"), ModuleRegistry.discover(List.of()),
                id -> ModuleState.ACTIVE);
        service.openCompany(service.createCompany("Asha Traders", 4, LocalDate.of(2026, 4, 1)).id());
        Map<String, ModuleState> states = Map.of("accounting", ModuleState.ACTIVE, "banking", ModuleState.READ_ONLY);
        ScreenCatalogue catalogue = ScreenCatalogue.of(List.of(
                provider("accounting", NavigationEntry.of("journal", Section.TRANSACTIONS, "Journal", 10,
                        c -> screen("journal screen", c)).withShortcut(new KeyCodeCombination(KeyCode.F7))),
                provider("banking", NavigationEntry.of("reconcile", Section.BANKING, "Reconcile", 10,
                        c -> screen("reconcile screen", c)))), states::get);
        FiscalYear year = new FiscalYear("fy", "FY 2026-27", LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                false);
        MainWindow window = new MainWindow(service, catalogue, Clock.systemDefaultZone(), year, () -> { });
        Scene scene = new Scene(window, 900, 600);
        window.installShortcuts(scene);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
    }

    @Test
    @DisplayName("S-1: a shortcut opens its screen from anywhere; the company and year are shown")
    void shortcut() {
        assertThat(lookup("#companyBar").queryAs(Label.class).getText()).isEqualTo("Asha Traders   ·   FY 2026-27");
        clickOn("#navigation");
        type(KeyCode.F7);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(lookup("journal screen").tryQuery()).isPresent();
        assertThat(lookup("#readOnlyBanner").tryQuery()).isEmpty();
        assertThat(seenState.get()).isEqualTo(ModuleState.ACTIVE);
    }

    @Test
    @DisplayName("a read-only module's screen opens under a banner and is told it is read-only")
    void readOnly() {
        interact(() -> ((MainWindow) lookup("#mainWindow").query()).open("reconcile", Map.of()));

        assertThat(lookup("reconcile screen").tryQuery()).isPresent();
        assertThat(lookup("#readOnlyBanner").queryAs(Label.class).getText()).startsWith("Read only");
        assertThat(seenState.get()).isEqualTo(ModuleState.READ_ONLY);
    }

    @Test
    @DisplayName("a screen with unsaved work stays open on Ctrl+W until it agrees to close")
    void refusesToClose() {
        clickOn("#navigation");
        type(KeyCode.F7);
        TabPane tabs = lookup("#workArea").query();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(tabs.getTabs()).as("F7 opened the screen").hasSize(1);
        closable = false;
        press(KeyCode.CONTROL).press(KeyCode.W).release(KeyCode.W).release(KeyCode.CONTROL);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(tabs.getTabs()).hasSize(1);

        closable = true;
        press(KeyCode.CONTROL).press(KeyCode.W).release(KeyCode.W).release(KeyCode.CONTROL);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(tabs.getTabs()).isEmpty();
    }
}
