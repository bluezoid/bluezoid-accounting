package com.accubook.ui.shell;

import com.accubook.accounting.chart.FiscalYear;
import com.accubook.app.company.CompanyService;
import com.accubook.ui.api.Screen;
import com.accubook.ui.api.Section;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The window a company is worked in: the company and year at the top,
 * navigation on the left, screens as tabs, messages at the bottom. Each
 * screen's shortcut opens it from anywhere (S-1); Ctrl+W closes a tab.
 */
final class MainWindow extends BorderPane {

    private final CompanyService service;
    private final ScreenCatalogue catalogue;
    private final Clock clock;
    private final FiscalYear year;
    private final Label status = new Label();
    private final TabPane tabs = new TabPane();
    private final Map<String, Tab> open = new HashMap<>();

    MainWindow(CompanyService service, ScreenCatalogue catalogue, Clock clock, FiscalYear year, Runnable changeCompany) {
        this.service = service;
        this.catalogue = catalogue;
        this.clock = clock;
        this.year = year;
        setId("mainWindow");

        Label company = new Label(service.currentCompany().orElseThrow().name() + "   ·   " + year.name()
                + (year.closed() ? " (closed)" : ""));
        company.setId("companyBar");
        company.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button change = new Button("Change company");
        change.setId("changeCompanyButton");
        change.setOnAction(e -> {
            if (closeAll()) {
                changeCompany.run();
            }
        });
        HBox top = new HBox(12, company, gap, change);
        top.setPadding(new Insets(8, 12, 8, 12));
        setTop(top);

        setLeft(navigation());
        tabs.setId("workArea");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        setCenter(tabs);
        status.setId("status");
        status.setPadding(new Insets(4, 12, 4, 12));
        setBottom(status);
    }

    private TreeView<String> navigation() {
        TreeItem<String> root = new TreeItem<>("AccuBook");
        Map<TreeItem<String>, String> ids = new HashMap<>();
        for (Section section : catalogue.sections()) {
            TreeItem<String> group = new TreeItem<>(section.title());
            group.setExpanded(true);
            for (ScreenCatalogue.Available screen : catalogue.in(section)) {
                KeyCombination key = screen.entry().shortcut();
                TreeItem<String> item = new TreeItem<>(screen.entry().title()
                        + (key == null ? "" : "   " + key.getDisplayText()));
                ids.put(item, screen.entry().id());
                group.getChildren().add(item);
            }
            root.getChildren().add(group);
        }
        TreeView<String> tree = new TreeView<>(root);
        tree.setId("navigation");
        tree.setShowRoot(false);
        tree.setPrefWidth(230);
        Runnable openSelected = () -> {
            TreeItem<String> item = tree.getSelectionModel().getSelectedItem();
            if (item != null && ids.containsKey(item)) {
                open(ids.get(item), Map.of());
            }
        };
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                openSelected.run();
            }
        });
        tree.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelected.run();
                e.consume();
            }
        });
        return tree;
    }

    /** Shortcuts work wherever focus is, once the window is in a scene. */
    void installShortcuts(Scene scene) {
        for (ScreenCatalogue.Available screen : catalogue.all()) {
            if (screen.entry().shortcut() != null) {
                scene.getAccelerators().put(screen.entry().shortcut(), () -> open(screen.entry().id(), Map.of()));
            }
        }
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN), () -> {
            Tab tab = tabs.getSelectionModel().getSelectedItem();
            if (tab != null && mayClose(tab)) {
                tabs.getTabs().remove(tab);
            }
        });
    }

    /** Open a screen, or bring it forward if it is open. */
    void open(String id, Map<String, String> arguments) {
        Tab existing = open.get(id);
        if (existing != null) {
            tabs.getSelectionModel().select(existing);
            return;
        }
        Optional<ScreenCatalogue.Available> found = catalogue.find(id);
        if (found.isEmpty()) {
            status.setText("That screen is not in this edition.");
            return;
        }
        ScreenCatalogue.Available available = found.get();
        ShellScreenContext context = new ShellScreenContext(service, clock, available.state(),
                () -> year.startDate(), status::setText, this::open);
        Screen screen = available.entry().factory().apply(context);
        Node view = screen.view();
        Tab tab = new Tab(available.entry().title(), available.readOnly() ? withBanner(view, available) : view);
        tab.setId("tab-" + id);
        tab.setUserData(screen);
        tab.setOnCloseRequest(e -> {
            if (!screen.canClose()) {
                e.consume();
            }
        });
        tab.setOnClosed(e -> open.remove(id));
        tab.setOnSelectionChanged(e -> {
            if (tab.isSelected()) {
                screen.shown();
            }
        });
        open.put(id, tab);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    /** READ_ONLY explained, not hidden (UPGRADE_DOWNGRADE.md): history stays visible. */
    private static Node withBanner(Node view, ScreenCatalogue.Available available) {
        Label banner = new Label("Read only: this edition or licence does not allow changes to "
                + available.module() + ". Your records stay visible and can be exported.");
        banner.setId("readOnlyBanner");
        banner.setWrapText(true);
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setPadding(new Insets(6, 12, 6, 12));
        banner.setStyle("-fx-background-color: #fff3cd; -fx-text-fill: #664d03;");
        VBox box = new VBox(banner, view);
        VBox.setVgrow(view, Priority.ALWAYS);
        return box;
    }

    private static boolean mayClose(Tab tab) {
        return !(tab.getUserData() instanceof Screen screen) || screen.canClose();
    }

    private boolean closeAll() {
        for (Tab tab : tabs.getTabs()) {
            if (!mayClose(tab)) {
                tabs.getSelectionModel().select(tab);
                return false;
            }
        }
        tabs.getTabs().clear();
        return true;
    }

    /** The year to work in: the one holding today, else the latest. */
    static FiscalYear workingYear(java.util.List<FiscalYear> years, LocalDate today) {
        return years.stream().filter(y -> y.contains(today)).findFirst()
                .orElseGet(() -> years.stream().max(java.util.Comparator.comparing(FiscalYear::startDate))
                        .orElseThrow());
    }
}
