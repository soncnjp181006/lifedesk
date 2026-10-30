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
    public enum ViewId {
        DASHBOARD("Dashboard", "layout-dashboard"), TASKS("Công việc", "list-checks"),
        NOTES("Ghi chú", "file-text"),
        FINANCE("Tài chính", "wallet"), HABITS("Thói quen & Mục tiêu", "repeat"),
        HEALTH("Sức khỏe", "heart-pulse"),
        LEARNING("Học tập", "book-open"), FILES("Tệp", "folder"),
        AI("Trợ lý AI", "bot"), SEARCH("Kết quả tìm kiếm", "search"), SETTINGS("Cài đặt", "settings");

        public final String label;
        /** File name under /icons, without extension. */
        public final String icon;

        ViewId(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private final Stage stage;
    private final Scene scene;
    private final AppContext ctx;
    /** Sidebar order; position in this list is the Ctrl+n shortcut digit (10th = Ctrl+0). */
    private static final List<ViewId> NAV_ORDER = List.of(ViewId.DASHBOARD, ViewId.TASKS, ViewId.NOTES,
            ViewId.FINANCE, ViewId.HABITS, ViewId.HEALTH, ViewId.LEARNING, ViewId.FILES,
            ViewId.AI);
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final VBox sidebarBox = new VBox(4);
    private final Map<ViewId, View> views = new HashMap<>();
    private final Map<ViewId, Supplier<View>> viewFactories = new HashMap<>();
    private ViewId current = null;

    private Label statusLabel;
    private Label notifBadge;
    private Label brandTitleLabel;
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
        root.setTop(buildTopBar());
        root.setLeft(buildSidebar());
        content.getStyleClass().add("content-area");
        ScrollPane centerScroll = new ScrollPane(content);
        centerScroll.setFitToWidth(true);
        // Fill the window when the view is short, but scroll instead of squeezing
        // when it is tall (fitToHeight would compress the dashboard charts).
        content.minHeightProperty().bind(centerScroll.viewportBoundsProperty()
                .map(b -> b.getHeight()));
        centerScroll.setStyle("-fx-background-color: transparent;");
        root.setCenter(centerScroll);
        root.setBottom(buildStatusBar());
        installShortcuts();
        showView(ViewId.DASHBOARD);
    }

    // ------------------------------------------------------------------ chrome

    private Node buildTopBar() {
        HBox bar = new HBox(12);
        bar.getStyleClass().add("topbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        Button collapseBtn = new Button("", new IconView("menu", 17));
        collapseBtn.getStyleClass().add("icon-btn");
        collapseBtn.setTooltip(new Tooltip("Thu gọn thanh bên"));
        collapseBtn.setOnAction(e -> toggleSidebar());

        searchField = new TextField();
        searchField.setPromptText("Tìm kiếm toàn cục  (Ctrl+K)");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.SOMETIMES);
        searchField.setOnAction(e -> {
            String q = searchField.getText();
            if (q != null && !q.isBlank()) {
                showSearch(q);
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // Theme selector
        var themeCombo = new javafx.scene.control.ComboBox<String>();
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

        bar.getChildren().addAll(collapseBtn, searchField, spacer, themeCombo, bellStack, settingsBtn);
        return bar;
    }

    private Node buildSidebar() {
        sidebarBox.getStyleClass().add("sidebar");
        sidebarCollapsed = ctx.config().sidebarCollapsed;
        brandBox = new VBox(1);
        brandTitleLabel = new Label("LifeDesk");
        brandTitleLabel.getStyleClass().add("brand-title");
        Label brandSub = new Label("Trợ lý cá nhân");
        brandSub.getStyleClass().add("brand-subtitle");
        brandBox.getChildren().addAll(brandTitleLabel, brandSub);
        Region brandSpacer = new Region();
        brandSpacer.setMinHeight(8);
        sidebarBox.getChildren().addAll(brandBox, brandSpacer);
        rebuildSidebarButtons();
        VBox.setVgrow(sidebarBox, Priority.ALWAYS);
        return sidebarBox;
    }

    private void rebuildSidebarButtons() {
        // Remove previous nav buttons, keep the brand block (first two children).
        if (sidebarBox.getChildren().size() > 2) {
            sidebarBox.getChildren().remove(2, sidebarBox.getChildren().size());
        }
        // Collapsed rail shows icons only; the brand block disappears instead of
        // shrinking to a lone "L" that reads like a stray character.
        brandBox.setVisible(!sidebarCollapsed);
        brandBox.setManaged(!sidebarCollapsed);
        if (sidebarCollapsed) {
            sidebarBox.setStyle("-fx-min-width: 58;");
        } else {
            sidebarBox.setStyle("");
        }
        for (ViewId id : ViewId.values()) {
            if (id == ViewId.SEARCH) {
                continue; // opened via search action only
            }
            Button btn = new Button(sidebarCollapsed ? "" : id.label, new IconView(id.icon, 16));
            btn.getStyleClass().add("nav-item");
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setAlignment(sidebarCollapsed ? Pos.CENTER : Pos.CENTER_LEFT);
            if (id == current) {
                btn.getStyleClass().add("active");
            }
            btn.setOnAction(e -> showView(id));
            String hint = shortcutHint(id);
            btn.setTooltip(new Tooltip(hint == null ? id.label : id.label + "  (" + hint + ")"));
            sidebarBox.getChildren().add(btn);
        }
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
        for (int i = 0; i < NAV_ORDER.size(); i++) {
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
        if (i < 0) {
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
        }
        current = id;
        view.ensureBuilt();
        content.getChildren().setAll(view);
        VBox.setVgrow(view, Priority.ALWAYS);
        rebuildSidebarButtons();
        view.onShow();
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
        switch (docType) {
            case "TASK" -> {
                showView(ViewId.TASKS);
                views.get(ViewId.TASKS).openRecord(refId);
            }
            case "NOTE" -> {
                showView(ViewId.NOTES);
                views.get(ViewId.NOTES).openRecord(refId);
            }
            case "TRANSACTION" -> {
                showView(ViewId.FINANCE);
                views.get(ViewId.FINANCE).openRecord(refId);
            }
            case "HABIT", "GOAL" -> {
                showView(ViewId.HABITS);
                views.get(ViewId.HABITS).openRecord(refId);
            }
            case "COURSE", "FLASHCARD" -> {
                showView(ViewId.LEARNING);
                views.get(ViewId.LEARNING).openRecord(refId);
            }
            case "HEALTH" -> {
                showView(ViewId.HEALTH);
                views.get(ViewId.HEALTH).openRecord(refId);
            }
            default -> status("Không có liên kết tới loại bản ghi: " + docType);
        }
    }

    public void applyTheme() {
        scene.getStylesheets().setAll(ctx.themeManager().stylesheetsFor(ctx.config().theme));
        // Dialogs live in their own windows - hand them the active theme explicitly.
        Dialogs.setGlobalStylesheets(scene.getStylesheets());
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
                        openRecord(viewFromRef(item.refView), item.refId);
                    } catch (RuntimeException ex) {
                        status("Không mở được đích thông báo");
                    }
                });
                row.setCursor(javafx.scene.Cursor.HAND);
            }
            notifList.getChildren().add(row);
        }
    }

    /**
     * Module key used by ReminderScheduler / search hits → the document type that
     * {@link #openRecord} understands. Total on purpose: a wrong-but-quiet jump to
     * Tasks is worse than staying put, so an unmapped key keeps its own name and
     * openRecord's default branch reports it.
     */
    private String viewFromRef(String refView) {
        return switch (refView) {
            case "tasks" -> "TASK";
            case "notes" -> "NOTE";
            case "habits" -> "HABIT";
            case "finance" -> "TRANSACTION";
            case "learning" -> "COURSE";
            case "health" -> "HEALTH";
            default -> refView;
        };
    }

    public void onReady() {
        ctx.notifications().push("Chào mừng", "LifeDesk đã sẵn sàng. Dữ liệu: " + ctx.dataDir(), "INFO", "", "");
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

    public AppConfig config() {
        return ctx.config();
    }
}
