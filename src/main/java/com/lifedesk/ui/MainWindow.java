package com.lifedesk.ui;

import com.lifedesk.app.AppContext;
import com.lifedesk.config.AppConfig;
import com.lifedesk.notifications.NotificationItem;
import com.lifedesk.ui.components.IconView;
import com.lifedesk.ui.components.Card;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Application shell: collapsible sidebar, top bar (global search, theme,
 * notifications, settings), content area and status bar. Owns navigation
 * between module views.
 */
public class MainWindow {

    public enum ViewId {
        // Vietnamese-first: the landing page used to read "Tổng quan" while the rail
        // said "Dashboard". With the in-page title gone the rail is the only place the
        // name appears, so the one copy left has to be the product's own language.
        DASHBOARD("Tổng quan", "layout-dashboard", null, null),
        TASKS("Công việc", "list-checks", "tasks", "TASK"),
        NOTES("Ghi chú", "file-text", "notes", "NOTE"),
        FINANCE("Tài chính", "wallet", "finance", "TRANSACTION"),
        HABITS("Thói quen & Mục tiêu", "repeat", "habits", "HABIT", "GOAL"),
        HEALTH("Sức khỏe", "heart-pulse", "health", "HEALTH"),
        LEARNING("Học tập", "book-open", "learning", "COURSE", "FLASHCARD"),
        FILES("Tệp", "folder", null, null),
        AI("Trợ lý AI", "bot", null, null),
        AGENT("Agent", "clipboard-list", null, null),
        RSS("RSS", "scroll-text", null, null),
        LIBRARY("Thư viện", "library-big", null, null),
        SEARCH("Kết quả tìm kiếm", "search", null, null),
        SETTINGS("Cài đặt", "settings", null, null);

        public final String label;
        /** File name under /icons, without extension. */
        public final String icon;
        /**
         * Module key used by ReminderScheduler / notification producers, or null
         * for views that are not a data module.
         */
        public final String moduleKey;
        /** SearchDoc types this view can open (one entry per supported doc type). */
        public final List<String> docTypes;

        ViewId(String label, String icon, String moduleKey, String... docTypes) {
            this.label = label;
            this.icon = icon;
            this.moduleKey = moduleKey;
            this.docTypes = docTypes == null ? List.of() : List.of(docTypes);
        }

        private static final Map<String, ViewId> BY_DOC_TYPE = new HashMap<>();
        private static final Map<String, ViewId> BY_MODULE_KEY = new HashMap<>();

        static {
            for (ViewId v : values()) {
                for (String t : v.docTypes) {
                    BY_DOC_TYPE.put(t, v);
                }
                if (v.moduleKey != null) {
                    BY_MODULE_KEY.put(v.moduleKey, v);
                }
            }
        }

        /** View owning a search/notification doc type, or null when unmapped. */
        public static ViewId byDocType(String docType) {
            return BY_DOC_TYPE.get(docType);
        }

        /** View owning a module key ("tasks", "notes", ...), or null when unmapped. */
        public static ViewId byModuleKey(String moduleKey) {
            return BY_MODULE_KEY.get(moduleKey);
        }
    }

    private final Stage stage;
    private final Scene scene;
    private final AppContext ctx;
    /**
     * The sidebar, in display order. One declaration drives the rail, the section
     * headers and the Ctrl+1..Ctrl+0 digits, so the three can never drift apart.
     * SEARCH is absent (it opens from the search field) and SETTINGS is absent
     * (the top-bar gear and Ctrl+, already reach it — a nav row for it was a third
     * door to the same room).
     */
    private record NavSection(String title, ViewId... items) {
    }

    private static final List<NavSection> NAV = List.of(
            new NavSection("TỔNG QUAN", ViewId.DASHBOARD),
            new NavSection("QUẢN LÝ", ViewId.TASKS, ViewId.NOTES, ViewId.FINANCE, ViewId.HABITS,
                    ViewId.HEALTH, ViewId.LEARNING),
            new NavSection("TRỢ LÝ", ViewId.AI, ViewId.AGENT),
            new NavSection("KHÁC", ViewId.FILES, ViewId.RSS, ViewId.LIBRARY));
    /** Flat rail order; position in this list is the Ctrl digit (10th = Ctrl+0). */
    private static final List<ViewId> NAV_ITEMS = NAV.stream()
            .flatMap(s -> java.util.Arrays.stream(s.items()))
            .toList();
    /** Digit keys and their labels, index-aligned with NAV_ITEMS (10 digits only). */
    private static final KeyCode[] DIGIT_KEYS = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3,
            KeyCode.DIGIT4, KeyCode.DIGIT5, KeyCode.DIGIT6, KeyCode.DIGIT7, KeyCode.DIGIT8,
            KeyCode.DIGIT9, KeyCode.DIGIT0};
    private static final String[] DIGIT_LABELS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    /** No constructor gaps: .sidebar / .topbar / .statusbar own spacing in CSS. */
    private final VBox sidebarBox = new VBox();
    /** The scrollable part of the rail: group headings + module buttons. */
    private final VBox navList = new VBox(4);
    private javafx.scene.control.ScrollPane navScroll;
    private final Map<ViewId, View> views = new HashMap<>();
    private final Map<ViewId, Supplier<View>> viewFactories = new HashMap<>();
    /** Sidebar buttons, kept so navigation only re-tints a class instead of rebuilding. */
    private final Map<ViewId, Button> navButtons = new HashMap<>();
    /** Chord → the view that answers it while visible, so two views can share one key. */
    private final Map<KeyCombination, Map<ViewId, Runnable>> viewKeys = new HashMap<>();
    /** Chord → shell fallback, used when no visible view claims the key. */
    private final Map<KeyCombination, Runnable> shellKeys = new HashMap<>();
    /** Scroll offset per view, restored when the view is shown again. */
    private final Map<ViewId, Double> scrollPositions = new HashMap<>();
    private ScrollPane centerScroll;
    /**
     * "Reduce motion" honoured from the environment: entry movement is a nicety,
     * and for some users it is the difference between a usable app and a headache.
     * Set by -Dlifedesk.reduceMotion=true or LIFEDESK_REDUCE_MOTION=1.
     */
    private static final boolean REDUCE_MOTION = Boolean.getBoolean("lifedesk.reduceMotion")
            || "1".equals(System.getenv("LIFEDESK_REDUCE_MOTION"));
    private javafx.animation.TranslateTransition previousEnter;
    private ViewId current = null;

    private Label statusLabel;
    private Label notifBadge;
    /** Top-bar theme picker; applyTheme() is the only place that syncs its label. */
    private javafx.scene.control.MenuButton themeMenu;
    private Label brandTitleLabel;
    private Label brandMark;
    private VBox brandNames;
    private Button collapseBtn;
    private VBox brandBox;
    private Popup notifPopup;
    private VBox notifList;
    private TextField searchField;
    /** The gear in the top bar: it is the "you are here" mark for the settings page. */
    private Button settingsBtn;
    private boolean sidebarCollapsed;

    public MainWindow(Stage stage, Scene scene, AppContext ctx) {
        this.stage = stage;
        this.scene = scene;
        this.ctx = ctx;
        registerFactories();
        applyTheme();
    }

    private void registerFactories() {
        viewFactories.put(ViewId.DASHBOARD, () -> new com.lifedesk.ui.views.DashboardView(ctx, this));
        viewFactories.put(ViewId.TASKS, () -> new com.lifedesk.ui.views.TasksView(ctx, this));
        viewFactories.put(ViewId.NOTES, () -> new com.lifedesk.ui.views.NotesView(ctx, this));
        viewFactories.put(ViewId.FINANCE, () -> new com.lifedesk.ui.views.FinanceView(ctx, this));
        viewFactories.put(ViewId.HABITS, () -> new com.lifedesk.ui.views.HabitsView(ctx, this));
        viewFactories.put(ViewId.HEALTH, () -> new com.lifedesk.ui.views.HealthView(ctx, this));
        viewFactories.put(ViewId.LEARNING, () -> new com.lifedesk.ui.views.LearningView(ctx, this));
        viewFactories.put(ViewId.FILES, () -> new com.lifedesk.ui.views.FilesView(ctx, this));
        viewFactories.put(ViewId.AI, () -> new com.lifedesk.ui.views.AiChatView(ctx, this));
        viewFactories.put(ViewId.AGENT, () -> new com.lifedesk.ui.views.AgentView(ctx, this));
        viewFactories.put(ViewId.RSS, () -> new com.lifedesk.ui.views.RssView(ctx, this));
        viewFactories.put(ViewId.LIBRARY, () -> new com.lifedesk.ui.views.LibraryView(ctx, this));
        viewFactories.put(ViewId.SEARCH, () -> new com.lifedesk.ui.views.SearchResultsView(ctx, this));
        viewFactories.put(ViewId.SETTINGS, () -> new com.lifedesk.ui.views.SettingsView(ctx, this));
    }

    public BorderPane root() {
        if (root.getCenter() == null) {
            buildLayout();
        }
        return root;
    }

    private void buildLayout() {
        content.getStyleClass().add("content-area");
        centerScroll = new ScrollPane(content);
        centerScroll.setFitToWidth(true);
        // Fill the window when the view is short, but scroll instead of squeezing
        // when it is tall (fitToHeight would compress the dashboard charts).
        content.minHeightProperty().bind(centerScroll.viewportBoundsProperty()
                .map(b -> b.getHeight()));

        // The rail runs the full window height and the bar belongs to the content
        // column. Previously the bar spanned the whole window with the collapse
        // button floating above the rail, which read as a floating leftover and
        // pushed the brand out of the column it was supposed to head.
        VBox mainColumn = new VBox(buildTopBar(), centerScroll, buildStatusBar());
        mainColumn.getStyleClass().add("main-column");
        mainColumn.setFillWidth(true);
        VBox.setVgrow(centerScroll, Priority.ALWAYS);

        HBox shell = new HBox(buildSidebar(), mainColumn);
        HBox.setHgrow(mainColumn, Priority.ALWAYS);
        root.setCenter(shell);
        installShortcuts();
        showView(ViewId.DASHBOARD);
    }

    // ------------------------------------------------------------------ chrome

    private Node buildTopBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("topbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("Tìm kiếm toàn cục  (Ctrl+K)");
        searchField.getStyleClass().add("search-field");
        searchField.setPrefWidth(360);
        searchField.setMaxWidth(380);
        HBox.setHgrow(searchField, Priority.NEVER);
        searchField.setOnAction(e -> {
            String q = searchField.getText();
            if (q != null && !q.isBlank()) {
                showSearch(q);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Theme picker: one button that names the active theme and lists the others,
        // with the current one ticked. A ComboBox showed the same name but could not
        // mark which entry was live, and switching it drove theme changes through a
        // value setter that then had to be re-synced — the menu just calls switchTheme.
        themeMenu = new javafx.scene.control.MenuButton(ctx.config().theme);
        themeMenu.getStyleClass().add("theme-menu");
        themeMenu.setTooltip(new Tooltip("Chủ đề giao diện"));
        javafx.scene.control.ToggleGroup themeGroup = new javafx.scene.control.ToggleGroup();
        for (String theme : ctx.themeManager().availableThemes()) {
            javafx.scene.control.RadioMenuItem item = new javafx.scene.control.RadioMenuItem(theme);
            item.setToggleGroup(themeGroup);
            item.setSelected(theme.equals(ctx.config().theme));
            item.setOnAction(e -> switchTheme(theme));
            themeMenu.getItems().add(item);
        }

        // Notification bell
        Button bell = new Button("", new IconView("bell", 17));
        bell.getStyleClass().add("icon-btn");
        notifBadge = new Label("0");
        notifBadge.getStyleClass().add("chip");
        notifBadge.setVisible(false);
        StackPane bellStack = new StackPane(bell, notifBadge);
        StackPane.setAlignment(notifBadge, Pos.TOP_RIGHT);
        StackPane.setMargin(notifBadge, new Insets(-4, -7, 0, 0));
        bell.setOnAction(e -> toggleNotifPopup(bellStack));
        ctx.notifications().changes().addListener(this::refreshNotifBadge);

        settingsBtn = new Button("", new IconView("settings", 17));
        settingsBtn.getStyleClass().add("icon-btn");
        settingsBtn.setTooltip(new Tooltip("Cài đặt"));
        settingsBtn.setOnAction(e -> showView(ViewId.SETTINGS));

        bar.getChildren().addAll(searchField, spacer, themeMenu, bellStack, settingsBtn);
        return bar;
    }

    private Node buildSidebar() {
        sidebarBox.getStyleClass().add("sidebar");
        sidebarCollapsed = ctx.config().sidebarCollapsed;

        brandMark = new Label("L");
        brandMark.getStyleClass().add("brand-mark");
        brandTitleLabel = new Label("LifeDesk");
        brandTitleLabel.getStyleClass().add("brand-title");
        Label brandSub = new Label("Trợ lý cá nhân");
        brandSub.getStyleClass().add("brand-subtitle");
        brandNames = new VBox(0, brandTitleLabel, brandSub);

        Button collapseBtn = new Button("", new IconView("menu", 16));
        collapseBtn.getStyleClass().add("icon-btn");
        collapseBtn.setTooltip(new Tooltip("Thu gọn thanh bên  (Ctrl+B)"));
        collapseBtn.setOnAction(e -> toggleSidebar());
        this.collapseBtn = collapseBtn;

        HBox brandRow = new HBox(9, brandMark, brandNames);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        Region brandSpacer = new Region();
        HBox.setHgrow(brandSpacer, Priority.ALWAYS);
        brandRow.getChildren().addAll(brandSpacer, collapseBtn);
        brandRow.getStyleClass().add("brand-row");
        brandBox = new VBox(brandRow);
        brandBox.setPadding(new Insets(0, 0, 10, 0));

        sidebarBox.getChildren().add(brandBox);
        // The rail scrolls. At the app's own minimum window height fourteen modules
        // plus four group headings do not fit: "Thư viện" and "Cài đặt" sat below the
        // fold with no way to reach them by mouse — and now that the page no longer
        // paints its own title, the rail is the only place that says where you are,
        // so it has to be able to show the item it is highlighting.
        navScroll = new javafx.scene.control.ScrollPane(navList);
        navScroll.setFitToWidth(true);
        navScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.getStyleClass().add("nav-scroll");
        navScroll.setAccessibleText("Danh sách module");
        VBox.setVgrow(navScroll, Priority.ALWAYS);
        sidebarBox.getChildren().add(navScroll);
        rebuildSidebarButtons();
        VBox.setVgrow(sidebarBox, Priority.ALWAYS);
        return sidebarBox;
    }

    /**
     * Scroll the rail just far enough to show the highlighted module. JavaFX has no
     * public "bring this node into view", so the arithmetic is done here: whichever way
     * the item hangs past the viewport, move the thumb by exactly that much.
     */
    private void revealActiveNav() {
        Button active = current == null ? null : navButtons.get(current);
        if (active == null || navScroll == null) {
            return;
        }
        javafx.geometry.Bounds b = active.localToScene(active.getBoundsInLocal());
        javafx.geometry.Bounds vp = navScroll.localToScene(navScroll.getViewportBounds());
        double past = b.getMaxY() - vp.getMaxY();
        double before = b.getMinY() - vp.getMinY();
        if (past <= 0 && before >= 0) {
            return;
        }
        double range = navScroll.getContent().getBoundsInLocal().getHeight()
                - navScroll.getViewportBounds().getHeight();
        if (range <= 0) {
            return;
        }
        double shift = (past > 0 ? past : before) / range;
        navScroll.setVvalue(Math.max(0, Math.min(1, navScroll.getVvalue() + shift)));
    }

    private void rebuildSidebarButtons() {
        // The brand block stays fixed; only the module list is rebuilt.
        navList.getChildren().clear();
        navButtons.clear();
        // Collapsed rail shows icons only. The brand text goes away but the mark and
        // the collapse control stay, otherwise the rail cannot be reopened.
        boolean showNames = !sidebarCollapsed;
        brandNames.setVisible(showNames);
        brandNames.setManaged(showNames);
        brandMark.setVisible(showNames);
        brandMark.setManaged(showNames);
        if (sidebarCollapsed) {
            sidebarBox.getStyleClass().add("collapsed");
            collapseBtn.setAlignment(Pos.CENTER);
        } else {
            sidebarBox.getStyleClass().remove("collapsed");
        }
        for (NavSection section : NAV) {
            if (showNames) {
                Label groupLabel = new Label(section.title());
                groupLabel.getStyleClass().add("nav-group");
                navList.getChildren().add(groupLabel);
            }
            for (ViewId id : section.items()) {
                // Iconic pattern: a user-picked icon (config) replaces the default.
                String icon = ctx.config().iconOverrides.getOrDefault(id.name(), id.icon);
                Button btn = new Button(sidebarCollapsed ? "" : id.label, new IconView(icon, 17));
                btn.getStyleClass().add("nav-item");
                btn.setMaxWidth(Double.MAX_VALUE);
                btn.setAlignment(sidebarCollapsed ? Pos.CENTER : Pos.CENTER_LEFT);
                if (id == current) {
                    btn.getStyleClass().add("active");
                }
                btn.setOnAction(e -> showView(id));
                String hint = shortcutHint(id);
                btn.setTooltip(new Tooltip(hint == null ? id.label : id.label + "  (" + hint + ")"));
                attachIconMenu(btn, id.name());
                navButtons.put(id, btn);
                navList.getChildren().add(btn);
            }
        }
        // Rebuilding replaced every button, so the highlighted one may now sit outside
        // the viewport the old one was scrolled to.
        javafx.application.Platform.runLater(this::revealActiveNav);
    }

    /**
     * Mark the active entry without touching the rest: creating ~13 buttons and
     * icon shape copies on every navigation was a measurable part of the switch lag.
     */
    private void updateNavSelection() {
        navButtons.forEach((id, btn) -> {
            boolean active = id == current;
            if (active && !btn.getStyleClass().contains("active")) {
                btn.getStyleClass().add("active");
            } else if (!active) {
                btn.getStyleClass().remove("active");
            }
        });
        // Two pages are not in the rail — settings is the gear, search is the field.
        // With no title painted inside the page, the control that opened them is the
        // only thing on screen that can say "this is where you are", so it carries the
        // marker instead of the rail.
        if (settingsBtn != null) {
            settingsBtn.getStyleClass().remove("origin-active");
            if (current == ViewId.SETTINGS) {
                settingsBtn.getStyleClass().add("origin-active");
            }
        }
        if (searchField != null) {
            searchField.getStyleClass().remove("origin-active");
            if (current == ViewId.SEARCH) {
                searchField.getStyleClass().add("origin-active");
            }
        }
    }

    /**
     * Iconic interaction: right-click an icon slot → searchable picker; picking
     * applies immediately and persists in config. The menu is built the first time
     * it is actually asked for, not once per rail row at startup — thirteen
     * context menus for a feature used maybe once.
     */
    private void attachIconMenu(Button btn, String configKey) {
        btn.setOnContextMenuRequested(e -> {
            if (btn.getContextMenu() != null) {
                return;
            }
            javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
            javafx.scene.control.MenuItem change = new javafx.scene.control.MenuItem("Đổi icon…");
            change.setOnAction(e2 -> com.lifedesk.ui.panes.IconPicker.show(btn, icon -> {
                ctx.config().iconOverrides.put(configKey, icon);
                saveConfig();
                status("Đã đổi icon: " + icon);
                rebuildSidebarButtons();
            }));
            javafx.scene.control.MenuItem reset = new javafx.scene.control.MenuItem("Khôi phục icon mặc định");
            reset.setOnAction(e2 -> {
                if (ctx.config().iconOverrides.remove(configKey) != null) {
                    saveConfig();
                    rebuildSidebarButtons();
                }
            });
            menu.getItems().addAll(change, reset);
            btn.setContextMenu(menu);
        });
    }

    private void toggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed;
        ctx.config().sidebarCollapsed = sidebarCollapsed;
        rebuildSidebarButtons();
        saveConfig();
    }

    /**
     * Shell-wide shortcuts. The audit found the app had none, so every action was a
     * mouse trip; these cover the two things users reach for constantly — jump to a
     * module, and start typing a search.
     */
    private void installShortcuts() {
        // Only the first 10 rail entries get Ctrl+1..Ctrl+0 (digit limit).
        for (int i = 0; i < Math.min(NAV_ITEMS.size(), DIGIT_KEYS.length); i++) {
            ViewId id = NAV_ITEMS.get(i);
            bindShellKey(new KeyCodeCombination(DIGIT_KEYS[i], KeyCombination.CONTROL_DOWN),
                    () -> showView(id));
        }
        bindShellKey(new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN), this::focusSearch);
        bindShellKey(new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN), this::toggleSidebar);
        bindShellKey(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
                () -> showView(ViewId.SETTINGS));
        bindShellKey(new KeyCodeCombination(KeyCode.ESCAPE), this::dismissOverlays);
    }

    /** "Ctrl+4" for FINANCE, or null for a rail entry with no shortcut. */
    private static String shortcutHint(ViewId id) {
        int i = NAV_ITEMS.indexOf(id);
        return i < 0 || i >= DIGIT_LABELS.length ? null : "Ctrl+" + DIGIT_LABELS[i];
    }

    /**
     * Register a chord the shell answers — unless the view on screen claims it.
     * NotesView binds Ctrl+1..6 to heading levels, so the shell cannot own the same
     * map slot: writing it straight into Scene.getAccelerators() meant whoever
     * registered last silenced the other for the rest of the session, which is
     * exactly how "Ctrl+2 stopped switching views once I had opened Ghi chú".
     */
    private void bindShellKey(KeyCodeCombination key, Runnable shellAction) {
        shellKeys.put(key, shellAction);
        scene.getAccelerators().put(key, () -> dispatchKey(key));
    }

    /**
     * Bind a shortcut owned by one view. Views are built once and cached, so the
     * binding lives for the whole session; the dispatcher keeps it firing only while
     * its own view is on screen, which is what the user expects from Ctrl+S / Ctrl+N.
     */
    public void addAccelerator(ViewId owner, KeyCodeCombination key, Runnable action) {
        viewKeys.computeIfAbsent(key, k -> new HashMap<>()).put(owner, action);
        scene.getAccelerators().putIfAbsent(key, () -> dispatchKey(key));
    }

    /** Route a chord to the visible view when it claims one, otherwise to the shell. */
    private void dispatchKey(KeyCombination key) {
        Map<ViewId, Runnable> owners = viewKeys.get(key);
        Runnable viewAction = owners == null ? null : owners.get(current);
        if (viewAction != null) {
            viewAction.run();
            return;
        }
        Runnable shellAction = shellKeys.get(key);
        if (shellAction != null) {
            shellAction.run();
        }
    }

    /** Put the caret in the global search box (Ctrl+K does the same). */
    public void focusSearch() {
        searchField.requestFocus();
        searchField.selectAll();
    }

    private void dismissOverlays() {
        if (notifPopup != null && notifPopup.isShowing()) {
            notifPopup.hide();
        } else if (searchField.isFocused()) {
            searchField.clear();
            content.requestFocus();
        }
    }

    private Node buildStatusBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("statusbar");
        statusLabel = new Label(IDLE_STATUS);
        // A 60-character absolute path on every screen is noise nobody reads; keep the
        // tail visible and the whole value one hover away (and in Settings → Dữ liệu).
        Label dataDirLabel = Card.dim(Fx.shortenPath(ctx.dataDir().toString()));
        dataDirLabel.setTooltip(new Tooltip(ctx.dataDir().toString()));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().addAll(statusLabel, spacer, dataDirLabel);
        return bar;
    }

    public void status(String message) {
        if (statusLabel == null) {
            return;
        }
        Fx.runLater(() -> {
            statusLabel.setText(message);
            // A receipt that never expires turns into a lie. The bar used to keep
            // "Đã lưu: …" for the rest of the session, so minutes later it still
            // claimed to describe what had just happened.
            if (statusFade != null) {
                statusFade.stop();
            }
            statusFade = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                    javafx.util.Duration.seconds(STATUS_HOLD_SECONDS),
                    e -> statusLabel.setText(IDLE_STATUS)));
            statusFade.play();
        });
    }

    private static final double STATUS_HOLD_SECONDS = 4.0;
    private static final String IDLE_STATUS = "Sẵn sàng";
    private javafx.animation.Timeline statusFade;

    // ------------------------------------------------------------------ navigation

    /**
     * What the shell believes it is showing. A test that reads the pixel tree can be
     * fooled by its own lookup; comparing the tree against this answers "did the app
     * switch, or did the probe look in the wrong place?".
     */
    public ViewId currentView() {
        return current;
    }

    public void showView(ViewId id) {
        View view = views.computeIfAbsent(id, k -> viewFactories.get(k).get());
        if (current == id && root.getCenter() != null) {
            view.onShow();
            return;
        }
        View old = views.get(current);
        if (old != null) {
            old.onHide();
            if (centerScroll != null) {
                scrollPositions.put(current, centerScroll.getVvalue());
            }
        }
        current = id;
        view.ensureBuilt();
        content.getChildren().setAll(view);
        VBox.setVgrow(view, Priority.ALWAYS);
        // The page no longer paints its own title, so the name has to live somewhere
        // that is not a second copy on screen: the window title (taskbar, Alt+Tab) and
        // the accessible name of the content area (screen reader, and the one place a
        // test can ask "which module is this?").
        stage.setTitle("LifeDesk — " + id.label);
        content.setAccessibleText(id.label);
        updateNavSelection();
        // Bring the highlighted item into view: the page no longer repeats its own
        // name, so a highlight the user cannot see is the only "where am I" they have.
        revealActiveNav();
        javafx.application.Platform.runLater(this::revealActiveNav);
        view.onShow();
        animateIn(view);
        if (centerScroll != null) {
            double v = scrollPositions.getOrDefault(id, 0.0);
            if (v > 0) {
                javafx.application.Platform.runLater(() -> centerScroll.setVvalue(v));
            }
        }
    }

    /**
     * A 190ms rise on entry. Only a translate — a transform, never a layout
     * property — so the frame that draws a newly switched page costs the same as
     * one that draws a settled page. The curve is an ease-out spline: fast start,
     * long soft landing, which is what makes a switch read as "moved" rather than
     * "jumped".
     */
    private void animateIn(Node view) {
        if (REDUCE_MOTION) {
            return;
        }
        if (previousEnter != null) {
            previousEnter.stop();
        }
        javafx.animation.TranslateTransition rise = new javafx.animation.TranslateTransition(
                javafx.util.Duration.millis(190), view);
        rise.setFromY(9);
        rise.setToY(0);
        rise.setInterpolator(javafx.animation.Interpolator.SPLINE(0.22, 1.0, 0.36, 1.0));
        rise.play();
        previousEnter = rise;
    }

    public void showSearch(String query) {
        showView(ViewId.SEARCH);
        View v = views.get(ViewId.SEARCH);
        if (v instanceof com.lifedesk.ui.views.SearchResultsView s) {
            s.search(query);
        }
    }

    /** Open the record from search results / notifications. */
    public void openRecord(String docType, String refId) {
        ViewId target = ViewId.byDocType(docType);
        if (target == null) {
            status("Không có liên kết tới loại bản ghi: " + docType);
            return;
        }
        showView(target);
        views.get(target).openRecord(refId);
    }

    /**
     * Jump to the module that owns a notification and open its record. Same three
     * steps as {@link #openRecord}, keyed by module instead of doc type — the two
     * used to be written out twice, once per caller.
     */
    private void openModuleRecord(String moduleKey, String refId) {
        ViewId target = ViewId.byModuleKey(moduleKey);
        if (target == null) {
            status("Không có liên kết tới loại bản ghi: " + moduleKey);
            return;
        }
        showView(target);
        views.get(target).openRecord(refId);
    }

    public void applyTheme() {
        scene.getStylesheets().setAll(ctx.themeManager().stylesheetsFor(ctx.config().theme));
        // Dialogs live in their own windows - hand them the active theme explicitly.
        Dialogs.setGlobalStylesheets(scene.getStylesheets());
        // The top bar shows the live theme name, whatever asked for the change.
        if (themeMenu != null) {
            themeMenu.setText(ctx.config().theme);
            for (javafx.scene.control.MenuItem mi : themeMenu.getItems()) {
                if (mi instanceof javafx.scene.control.RadioMenuItem radio) {
                    radio.setSelected(mi.getText().equals(ctx.config().theme));
                }
            }
        }
        // WebView panes embed the palette in their HTML, so a stylesheet swap
        // alone leaves them wearing the previous theme's colors.
        for (View v : views.values()) {
            v.onThemeChanged();
        }
    }

    /**
     * Switch theme by name and apply it live. Every caller (top bar, settings,
     * automation) goes through here, so the widget state is derived in applyTheme
     * rather than kept in step by each entry point.
     */
    public void switchTheme(String theme) {
        if (theme == null || theme.equals(ctx.config().theme)) {
            return;
        }
        ctx.config().theme = theme;
        applyTheme();
        saveConfig();
    }

    /** Persist config after a UI-initiated change. Shared with {@link View#saveConfig()}. */
    public void saveConfig() {
        Fx.async(ctx.executors().io(), () -> {
            new com.lifedesk.config.ConfigService(com.lifedesk.config.ConfigService.defaultConfigDir())
                    .save(ctx.config());
            ctx.persistAiSnapshot();
            return true;
        }, ok -> {
        }, err -> status("Lưu cấu hình thất bại: " + err.getMessage()));
    }

    // ------------------------------------------------------------------ notifications

    private void refreshNotifBadge() {
        Fx.runLater(() -> {
            int unread = ctx.notifications().unreadCount();
            notifBadge.setText(String.valueOf(Math.min(unread, 99)));
            notifBadge.setVisible(unread > 0);
            if (notifPopup != null && notifPopup.isShowing()) {
                fillNotifList();
            }
        });
    }

    private void toggleNotifPopup(Node anchor) {
        if (notifPopup != null && notifPopup.isShowing()) {
            notifPopup.hide();
            return;
        }
        notifPopup = new Popup();
        notifPopup.setAutoHide(true);
        VBox panel = new VBox(6);
        panel.getStyleClass().add("notif-popup");
        panel.setPrefWidth(360);
        panel.setPrefHeight(420);
        notifList = new VBox(6);
        ScrollPane scroll = new ScrollPane(notifList);
        scroll.setFitToWidth(true);
        Button markRead = new Button("Đánh dấu đã đọc");
        markRead.setOnAction(e -> {
            ctx.notifications().markAllRead();
            refreshNotifBadge();
        });
        Button clear = new Button("Xóa hết");
        clear.setOnAction(e -> {
            ctx.notifications().clear();
            refreshNotifBadge();
        });
        HBox actions = new HBox(8, markRead, clear);
        panel.getChildren().addAll(new Label("Thông báo"), actions, scroll);
        notifPopup.getContent().setAll(panel);
        fillNotifList();
        notifPopup.show(stage);
    }

    private void fillNotifList() {
        notifList.getChildren().clear();
        List<NotificationItem> items = ctx.notifications().all();
        if (items.isEmpty()) {
            notifList.getChildren().add(new Label("Chưa có thông báo nào."));
        }
        for (NotificationItem item : items) {
            VBox row = new VBox(2);
            row.getStyleClass().add("notif-item");
            if (!item.read) {
                row.getStyleClass().add("unread");
            }
            Label title = new Label(item.title);
            title.getStyleClass().add("section-label");
            Label msg = Card.dim(item.message);
            msg.setWrapText(true);
            Label time = Card.dim(item.createdAt.atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM")));
            row.getChildren().addAll(title, msg, time);
            boolean hasRef = item.refView != null && !item.refView.isBlank();
            if (hasRef) {
                row.setOnMouseClicked(e -> {
                    notifPopup.hide();
                    ctx.notifications().markAllRead();
                    refreshNotifBadge();
                    try {
                        openModuleRecord(item.refView, item.refId);
                    } catch (RuntimeException ex) {
                        status("Không mở được đích thông báo");
                    }
                });
                row.setCursor(javafx.scene.Cursor.HAND);
            }
            notifList.getChildren().add(row);
        }
    }

    public void onReady() {
        // Paint the bell badge from the notifications already on disk: without this the
        // count only appears once a new one fires, so last week's unread items stayed hidden.
        refreshNotifBadge();
        ctx.issueLog().addListener(() -> {
            var issues = ctx.issueLog().issues();
            if (!issues.isEmpty()) {
                var latest = issues.get(0);
                status("Lưu trữ: [" + latest.severity() + "] " + latest.message());
                if ("ERROR".equals(latest.severity())) {
                    ctx.notifications().push("Lỗi lưu trữ dữ liệu", latest.message(), "ERROR", "", "");
                }
            }
        });
    }

    public Stage stage() {
        return stage;
    }

    public AppContext context() {
        return ctx;
    }

    /**
     * A view this session has already opened, or null if it never has been. Does not
     * build it: diagnostics must not change what is on screen.
     */
    public View openedView(ViewId id) {
        return views.get(id);
    }

    public AppConfig config() {
        return ctx.config();
    }
}
