package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.IconView;
import com.lifedesk.ui.panes.AiSettingsPane;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Settings: appearance, AI providers (delegated to {@link AiSettingsPane}),
 * data directory, allowed roots, backups, reminders.
 */
public class SettingsView extends View {
    private AiSettingsPane aiPane;

    public SettingsView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "SETTINGS";
    }

    @Override
    protected Node build() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Giao diện", buildAppearanceTab()),
                new Tab("AI", buildAiTab()),
                new Tab("Dữ liệu & Backup", buildDataTab()),
                new Tab("Quyền truy cập tệp", buildRootsTab()),
                new Tab("Nhắc việc", buildRemindersTab()),
                new Tab("Giới thiệu", buildAboutTab()));
        return tabs;
    }

    // ------------------------------------------------------------------ appearance

    private Node buildAppearanceTab() {
        VBox box = new VBox(10);
        box.getChildren().add(new Label("Chủ đề:"));
        ToggleGroup group = new ToggleGroup();
        for (String theme : ctx.themeManager().availableThemes()) {
            RadioButton rb = new RadioButton(theme);
            rb.setToggleGroup(group);
            rb.setSelected(theme.equals(ctx.config().theme));
            rb.setOnAction(e -> {
                ctx.config().theme = theme;
                main.applyTheme();
                saveConfig();
            });
            box.getChildren().add(rb);
        }
        return wrapScroll(box);
    }

    // ------------------------------------------------------------------ AI

    private Node buildAiTab() {
        aiPane = new AiSettingsPane(ctx, main, this::saveConfig);
        return wrapScroll(aiPane);
    }

    // ------------------------------------------------------------------ data & backup

    private Node buildDataTab() {
        Label dirLabel = new Label("Thư mục dữ liệu: " + ctx.dataDir());
        dirLabel.setWrapText(true);

        Button backup = new Button("Tạo bản sao lưu ngay", new IconView("save", 15));
        backup.getStyleClass().add("primary-btn");
        backup.setOnAction(e -> Fx.async(ctx.executors().io(), () -> ctx.backup().backup(ctx.dataDir(),
                        ctx.dataDir().resolve("backups")),
                zip -> Dialogs.info("Sao lưu xong", "Đã tạo: " + zip),
                err -> Dialogs.error("Sao lưu thất bại", err.getMessage())));

        Button restore = new Button("Khôi phục từ bản sao lưu", new IconView("recycle", 15));
        restore.setOnAction(e -> restoreBackup());

        Button integrity = new Button("Kiểm tra toàn vẹn dữ liệu", new IconView("flask-conical", 15));
        integrity.setOnAction(e -> Fx.async(ctx.executors().io(),
                () -> ctx.backup().integrityCheck(ctx.dataDir()),
                corrupt -> Dialogs.info("Kiểm tra toàn vẹn", corrupt.isEmpty()
                        ? "Tất cả file JSON đều đọc tốt"
                        : "File hỏng:\n- " + String.join("\n- ", corrupt)),
                err -> Dialogs.error("Lỗi kiểm tra", err.getMessage())));

        Button openData = new Button("Mở thư mục dữ liệu", new IconView("folder-open", 15));
        openData.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().open(ctx.dataDir().toFile());
            } catch (Exception ex) {
                Dialogs.error("Lỗi", ex.getMessage());
            }
        });

        VBox box = new VBox(10, new Label("Vị trí dữ liệu (local-first, không cloud):"), dirLabel,
                new HBox(10, backup, restore, integrity, openData),
                new Label("Bản sao lưu nằm trong <data>/backups — file zip có manifest + SHA-256."),
                new Label("Đổi thư mục dữ liệu: đóng app, đặt thuộc tính -Dlifedesk.data=<đường dẫn>, "
                        + "hoặc chỉnh %APPDATA%/LifeDesk/config/config/app-config.json."));
        return wrapScroll(box);
    }

    private void restoreBackup() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Chọn bản sao lưu");
        chooser.setInitialDirectory(ctx.dataDir().resolve("backups").toFile());
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("LifeDesk backup", "*.zip"));
        java.io.File file = chooser.showOpenDialog(main.stage());
        if (file == null) {
            return;
        }
        if (!Dialogs.confirm("Khôi phục dữ liệu",
                "Khôi phục sẽ GHI ĐÈ dữ liệu hiện tại bằng nội dung bản sao lưu.\n"
                        + "File hiện tại không có trong bản sao lưu sẽ được chuyển sang backups/orphaned-*.\nTiếp tục?")) {
            return;
        }
        Fx.async(ctx.executors().io(), () -> ctx.backup().restore(file.toPath(), ctx.dataDir()),
                report -> {
                    Dialogs.info("Khôi phục xong",
                            "Đã khôi phục " + report.restored() + " file."
                                    + (report.orphaned().isEmpty() ? "" : "\nFile dư được chuyển tới backups/orphaned-*: "
                                    + report.orphaned().size())
                                    + (report.problems().isEmpty() ? "" : "\nVấn đề: " + report.problems())
                                    + "\n\nKhởi động lại ứng dụng để nạp dữ liệu mới.");
                },
                err -> Dialogs.error("Khôi phục thất bại", err.getMessage()));
    }

    // ------------------------------------------------------------------ allowed roots

    private Node buildRootsTab() {
        VBox list = new VBox(6);
        rebuildRootsList(list);
        Button add = new Button("Cấp quyền thư mục", new IconView("plus", 15));
        add.getStyleClass().add("primary-btn");
        add.setOnAction(e -> {
            if (com.lifedesk.ui.RootGrants.grantViaDialog(ctx, main,
                    "Cấp quyền đọc cho LifeDesk",
                    "Cho phép đọc file trong:\n%s") != null) {
                saveConfig();
                rebuildRootsList(list);
            }
        });
        VBox box = new VBox(10, new Label("Các thư mục được cấp quyền (trình quản lý tệp):"),
                add, list);
        return wrapScroll(box);
    }

    private void rebuildRootsList(VBox list) {
        list.getChildren().clear();
        for (String root : ctx.config().allowedRoots) {
            HBox row = new HBox(8, new Label(root));
            Button remove = new Button("Thu hồi");
            remove.setOnAction(e -> {
                ctx.config().allowedRoots.remove(root);
                ctx.files().setAllowedRoots(ctx.config().allowedRoots);
                saveConfig();
                rebuildRootsList(list);
            });
            row.getChildren().add(remove);
            list.getChildren().add(row);
        }
    }

    // ------------------------------------------------------------------ reminders

    private Node buildRemindersTab() {
        CheckBox reminders = new CheckBox("Bật nhắc việc trong app");
        reminders.setSelected(ctx.config().remindersEnabled);
        reminders.setOnAction(e -> {
            ctx.config().remindersEnabled = reminders.isSelected();
            saveConfig();
        });
        Spinner<Integer> minutes = new Spinner<>(0, 120, ctx.config().reminderMinutesBefore);
        minutes.valueProperty().addListener((o, a, b) -> {
            ctx.config().reminderMinutesBefore = minutes.getValue();
            saveConfig();
        });

        VBox box = new VBox(12,
                new Label("Nhắc việc:"),
                reminders,
                new HBox(8, new Label("Nhắc trước (phút)"), minutes));
        return wrapScroll(box);
    }

    // ------------------------------------------------------------------ about

    private Node buildAboutTab() {
        VBox box = new VBox(10,
                new Label("LifeDesk 1.0.0"),
                new Label("Ứng dụng năng suất cá nhân offline-first: công việc, ghi chú markdown, tài chính, "
                        + "thói quen, sức khỏe, học tập, tệp, tìm kiếm và AI cục bộ."),
                new Label("Dữ liệu: JSON + Markdown thuần trong thư mục dữ liệu của bạn — không database server, không cloud."),
                new Label("Sức khỏe: chỉ ghi nhận thủ công, không chẩn đoán y tế."),
                new Label("AI: chỉ chạy theo provider bạn cấu hình; dữ liệu chỉ rời máy khi bật provider cloud."));
        return wrapScroll(box);
    }

    // ------------------------------------------------------------------ helpers

    private Node wrapScroll(Node content) {
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox padding = new VBox(scroll);
        padding.setPadding(new javafx.geometry.Insets(8));
        return padding;
    }

    @Override
    public void onShow() {
        if (aiPane != null) {
            aiPane.refreshStatus();
        }
    }
}
