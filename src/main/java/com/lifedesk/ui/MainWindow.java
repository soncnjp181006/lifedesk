package com.lifedesk.ui;

import com.lifedesk.app.AppContext;
import com.lifedesk.config.AppConfig;
import com.lifedesk.notifications.NotificationItem;
import com.lifedesk.notifications.NotificationService;
import com.lifedesk.ui.components.IconView;
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
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MainWindow.class);

    public enum ViewId {
        DASHBOARD("Dashboard", "layout-dashboard", null, null),
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
    /** Sidebar order; position in this list is the Ctrl+n shortcut digit (10th = Ctrl+0). */
    private static final List<ViewId> NAV_ORDER = List.of(ViewId.DASHBOARD, ViewId.TASKS, ViewId.NOTES,
            ViewId.FINANCE, ViewId.HABITS, ViewId.HEALTH, ViewId.LEARNING, ViewId.FILES,
            ViewId.AI, ViewId.AGENT);
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final VBox sidebarBox = new VBox(4);
    private final Map<ViewId, View> views = new HashMap<>();
    private final Map<ViewId, Supplier<View>> viewFactories = new HashMap<>();
    /** Sidebar buttons, kept so navigation only re-tints a class instead of rebuilding. */
    private final Map<ViewId, Button> navButtons = new HashMap<>();
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
    /** Top-bar theme selector; kept so switchTheme() can sync its shown value. */
    private javafx.scene.control.ComboBox<String> themeCombo;
    private Label brandTitleLabel;
    private Label brandMark;
    private VBox brandNames;
    private Button collapseBtn;
    private VBox brandBox;
    private Popup notifPopup;
    private VBox notifList;
    private TextField searchField;
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
        centerScroll.setStyle("-fx-background-color: transparent;");

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
        HBox bar = new HBox(12);
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

        // Theme selector
        themeCombo = new javafx.scene.control.ComboBox<>();
        themeCombo.getItems().addAll(ctx.themeManager().availableThemes());
        themeCombo.setValue(ctx.config().theme);
        themeCombo.setTooltip(new Tooltip("Chủ đề giao diện"));
        themeCombo.setPrefWidth(132);
        themeCombo.setOnAction(e -> {
            String theme = themeCombo.getValue();
            if (theme != null) {
                ctx.config().theme = theme;
                applyTheme();
                saveConfig();
            }
        });

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

        Button settingsBtn = new Button("", new IconView("settings", 17));
        settingsBtn.getStyleClass().add("icon-btn");
        settingsBtn.setTooltip(new Tooltip("Cài đặt"));
        settingsBtn.setOnAction(e -> showView(ViewId.SETTINGS));

        bar.getChildren().addAll(searchField, spacer, themeCombo, bellStack, settingsBtn);
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
        rebuildSidebarButtons();
        VBox.setVgrow(sidebarBox, Priority.ALWAYS);
        return sidebarBox;
    }

    private void rebuildSidebarButtons() {
        // Remove previous nav buttons, keep the brand block (first child).
        if (sidebarBox.getChildren().size() > 1) {
            sidebarBox.getChildren().remove(1, sidebarBox.getChildren().size());
        }
        navButtons.clear();
        // Collapsed rail shows icons only. The brand text goes away but the mark and
        // the collapse control stay, otherwise the rail cannot be reopened.
        boolean showNames = !sidebarCollapsed;
        brandNames.setVisible(showNames);
        brandNames.setManaged(showNames);
        brandMark.setVisible(showNames);
        brandMark.setManaged(showNames);
        if (sidebarCollapsed) {
            sidebarBox.setStyle("-fx-min-width: 68; -fx-pref-width: 68; -fx-max-width: 68;");
            collapseBtn.setAlignment(Pos.CENTER);
        } else {
            sidebarBox.setStyle("");
        }
        for (ViewId id : ViewId.values()) {
            if (id == ViewId.SEARCH) {
                continue; // opened via search action only
            }
            // Group markers: the rail is an information architecture, not a dump of
            // twelve equal rows. Collapsed mode has no room for the label.
            String group = id == ViewId.DASHBOARD ? "TỔNG QUAN"
                    : id == ViewId.TASKS ? "QUẢN LÝ"
                    : id == ViewId.AI ? "TRỢ LÝ"
                    : id == ViewId.RSS ? "KHÁC" : null;
            if (group != null && !sidebarCollapsed) {
                Label groupLabel = new Label(group);
                groupLabel.getStyleClass().add("nav-group");
                sidebarBox.getChildren().add(groupLabel);
            }
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
            btn.setTooltip(new Tooltip(hint == null ? id.label : id.label + "  (" + hint + ")"
                    + "  ·  chuột phải để đổi icon"));
            attachIconMenu(btn, id.name(), () -> rebuildSidebarButtons());
            navButtons.put(id, btn);
            sidebarBox.getChildren().add(btn);
        }
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
    }

    /**
     * Iconic interaction: right-click an icon slot → searchable picker;
     * picking applies immediately and persists in config.
     */
    private void attachIconMenu(javafx.scene.control.Button btn, String configKey, Runnable onRebuilt) {
        javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.MenuItem change = new javafx.scene.control.MenuItem("Đổi icon…");
        change.setOnAction(e -> com.lifedesk.ui.panes.IconPicker.show(btn, icon -> {
            ctx.config().iconOverrides.put(configKey, icon);
            saveConfig();
            status("Đã đổi icon: " + icon);
            onRebuilt.run();
        }));
        javafx.scene.control.MenuItem reset = new javafx.scene.control.MenuItem("Khôi phục icon mặc định");
        reset.setOnAction(e -> {
            if (ctx.config().iconOverrides.remove(configKey) != null) {
                saveConfig();
                onRebuilt.run();
            }
        });
        menu.getItems().addAll(change, reset);
        btn.setContextMenu(menu);
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
        // Only the first 10 sidebar entries get Ctrl+1..Ctrl+0 (digit limit).
        for (int i = 0; i < Math.min(NAV_ORDER.size(), 10); i++) {
            ViewId id = NAV_ORDER.get(i);
            KeyCodeCombination key = new KeyCodeCombination(navDigit(i), KeyCombination.CONTROL_DOWN);
            scene.getAccelerators().put(key, () -> showView(id));
        }
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN),
                this::focusSearch);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN),
                this::toggleSidebar);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.CONTROL_DOWN),
                () -> showView(ViewId.SETTINGS));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE), this::dismissOverlays);
    }

    private static KeyCode navDigit(int index) {
        return switch (index) {
            case 0 -> KeyCode.DIGIT1;
            case 1 -> KeyCode.DIGIT2;
            case 2 -> KeyCode.DIGIT3;
            case 3 -> KeyCode.DIGIT4;
            case 4 -> KeyCode.DIGIT5;
            case 5 -> KeyCode.DIGIT6;
            case 6 -> KeyCode.DIGIT7;
            case 7 -> KeyCode.DIGIT8;
            case 8 -> KeyCode.DIGIT9;
            default -> KeyCode.DIGIT0;
        };
    }

    /** "Ctrl+4" for FINANCE, or null for a view with no shortcut. */
    private static String shortcutHint(ViewId id) {
        int i = NAV_ORDER.indexOf(id);
        if (i < 0 || i > 9) {
            return null;
        }
        return "Ctrl+" + (i == 9 ? 0 : i + 1);
    }

    /**
     * Bind a shortcut owned by one view. Views are built once and cached, so the
     * binding lives for the whole session; the guard keeps it firing only while its
     * own view is on screen, which is what the user expects from Ctrl+S / Ctrl+N.
     */
    public void addAccelerator(ViewId owner, KeyCodeCombination key, Runnable action) {
        scene.getAccelerators().put(key, () -> {
            if (current == owner) {
                action.run();
            }
        });
    }

    private void focusSearch() {
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
        HBox bar = new HBox(12);
        bar.getStyleClass().add("statusbar");
        statusLabel = new Label("Sẵn sàng");
        Label dataDirLabel = new Label("Dữ liệu: " + ctx.dataDir());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().addAll(statusLabel, spacer, dataDirLabel);
        return bar;
    }

    public void status(String message) {
        if (statusLabel != null) {
            Fx.runLater(() -> statusLabel.setText(message));
        }
    }

    // ------------------------------------------------------------------ navigation

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
        updateNavSelection();
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

    /**
     * Build views ahead of the first click so navigation is instant.
     *
     * <p>Runs one view per pulse after startup instead of one big blocking burst,
     * and skips views whose whole surface is a WebView (Notes, RSS): those allocate
     * a WebKit page per visit, and paying that at startup would raise peak memory
     * for no benefit. Learning is warmed last because its review tab does create a
     * WebView — cheap once, but not something to pay for before the user asks.
     */
    private void prewarmViews() {
        List<ViewId> warm = List.of(ViewId.TASKS, ViewId.FINANCE, ViewId.HABITS, ViewId.HEALTH,
                ViewId.FILES, ViewId.AI, ViewId.AGENT, ViewId.SETTINGS,
                ViewId.LIBRARY, ViewId.DASHBOARD, ViewId.LEARNING);
        warmOne(warm, 0);
    }

    private void warmOne(List<ViewId> ids, int index) {
        if (index >= ids.size()) {
            return;
        }
        ViewId id = ids.get(index);
        javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(index == 0 ? 700 : 90));
        delay.setOnFinished(e -> {
            try {
                View v = views.computeIfAbsent(id, k -> viewFactories.get(k).get());
                v.ensureBuilt();
            } catch (Throwable ex) {
                // Speculative work must never reach the global crash handler: a broken
                // optional native (WebKit) or a fragile view would otherwise pop an
                // error dialog about a screen the user never asked to open. Drop the
                // half-built view so the real click retries it with a clean slate.
                views.remove(id);
                LOG.warn("Không dựng trước được view {}", id, ex);
            }
            warmOne(ids, index + 1);
        });
        delay.play();
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

    public void applyTheme() {
        scene.getStylesheets().setAll(ctx.themeManager().stylesheetsFor(ctx.config().theme));
        // Dialogs live in their own windows - hand them the active theme explicitly.
        Dialogs.setGlobalStylesheets(scene.getStylesheets());
        // WebView panes embed the palette in their HTML, so a stylesheet swap
        // alone leaves them wearing the previous theme's colors.
        for (View v : views.values()) {
            v.onThemeChanged();
        }
    }

    /** Switch theme by name and apply it live (settings UI + automation hook). */
    public void switchTheme(String theme) {
        if (themeCombo != null && !theme.equals(themeCombo.getValue())) {
            // Setting the value fires the combo's action handler, which applies
            // and persists — so the top bar never shows a stale theme name.
            themeCombo.setValue(theme);
        } else {
            ctx.config().theme = theme;
            applyTheme();
            saveConfig();
        }
    }

    private void saveConfig() {
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
        scroll.setStyle("-fx-background-color: transparent;");
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
            Label msg = new Label(item.message);
            msg.getStyleClass().add("dim-label");
            msg.setWrapText(true);
            Label time = new Label(item.createdAt.atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM")));
            time.getStyleClass().add("dim-label");
            row.getChildren().addAll(title, msg, time);
            boolean hasRef = item.refView != null && !item.refView.isBlank();
            if (hasRef) {
                row.setOnMouseClicked(e -> {
                    notifPopup.hide();
                    ctx.notifications().markAllRead();
                    refreshNotifBadge();
                    try {
                        ViewId target = ViewId.byModuleKey(item.refView);
                        if (target == null) {
                            status("Không có liên kết tới loại bản ghi: " + item.refView);
                        } else {
                            showView(target);
                            views.get(target).openRecord(item.refId);
                        }
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
        ctx.notifications().push("Chào mừng", "LifeDesk đã sẵn sàng. Dữ liệu: " + ctx.dataDir(), "INFO", "", "");
        refreshNotifBadge();
        prewarmViews();
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

    public AppConfig config() {
        return ctx.config();
    }
}
