package com.accubook.ui.shell;

import com.accubook.module.ModuleId;
import com.accubook.module.ModuleState;
import com.accubook.ui.api.NavigationEntry;
import com.accubook.ui.api.ScreenProvider;
import com.accubook.ui.api.Section;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which screens show, decided by module state alone - never by naming a plan. */
class ScreenCatalogueTest {

    private static ScreenProvider provider(String module, NavigationEntry... entries) {
        return new ScreenProvider() {
            @Override
            public ModuleId module() {
                return ModuleId.of(module);
            }

            @Override
            public List<NavigationEntry> entries() {
                return List.of(entries);
            }
        };
    }

    private static NavigationEntry entry(String id, Section section, int order) {
        return NavigationEntry.of(id, section, id, order, context -> null);
    }

    private static final Map<String, ModuleState> STATES = Map.of("accounting", ModuleState.ACTIVE,
            "banking", ModuleState.READ_ONLY, "payroll", ModuleState.DORMANT, "ocr", ModuleState.ABSENT);

    @Test
    @DisplayName("active and read-only modules show their screens; dormant and absent do not")
    void byState() {
        ScreenCatalogue catalogue = ScreenCatalogue.of(List.of(
                provider("accounting", entry("journal", Section.TRANSACTIONS, 10)),
                provider("banking", entry("reconcile", Section.BANKING, 10)),
                provider("payroll", entry("payslips", Section.TRANSACTIONS, 20)),
                provider("ocr", entry("scan", Section.TRANSACTIONS, 30))), STATES::get);

        assertEquals(List.of("journal", "reconcile"), catalogue.all().stream().map(a -> a.entry().id()).toList());
        assertFalse(catalogue.find("journal").orElseThrow().readOnly());
        assertTrue(catalogue.find("reconcile").orElseThrow().readOnly());
        assertEquals(List.of(Section.TRANSACTIONS, Section.BANKING), catalogue.sections());
    }

    @Test
    @DisplayName("within a section, by order then title")
    void ordering() {
        ScreenCatalogue catalogue = ScreenCatalogue.of(List.of(provider("accounting",
                entry("b", Section.MASTERS, 20), entry("z", Section.MASTERS, 10), entry("a", Section.MASTERS, 20))),
                STATES::get);

        assertEquals(List.of("z", "a", "b"), catalogue.in(Section.MASTERS).stream().map(a -> a.entry().id()).toList());
    }

    @Test
    @DisplayName("two screens with one id, or one key for two screens, are refused at start")
    void conflicts() {
        assertThrows(IllegalStateException.class, () -> ScreenCatalogue.of(List.of(
                provider("accounting", entry("x", Section.MASTERS, 1)),
                provider("banking", entry("x", Section.BANKING, 1))), STATES::get));
        KeyCodeCombination f5 = new KeyCodeCombination(KeyCode.F5);
        assertThrows(IllegalStateException.class, () -> ScreenCatalogue.of(List.of(provider("accounting",
                entry("payment", Section.TRANSACTIONS, 1).withShortcut(f5),
                entry("other", Section.TRANSACTIONS, 2).withShortcut(f5))), STATES::get));
    }
}
