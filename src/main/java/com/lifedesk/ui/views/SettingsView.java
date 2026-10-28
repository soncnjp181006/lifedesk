package com.lifedesk.ui.views;

import com.lifedesk.ai.AiProvider;
import com.lifedesk.ai.ModelInfo;
import com.lifedesk.app.AppContext;
import com.lifedesk.config.AiConfig;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.IconView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.List;

/**
 * Settings: appearance, AI providers (enable/disable, fetch & search models,
 * Ollama pull), data directory, allowed roots, backups, reminders.
 */
public class SettingsView extends View {
    private final TableView<ModelInfo> modelTable = new TableView<>();
    private final TextField modelSearch = new TextField();
    private final List<ModelInfo> fetchedModels = new java.util.ArrayList<>();
    private final Label aiStatusLabel = new Label("");
    private final Label activeModelLabel = new Label();
    private final ComboBox<String> modelProviderPick = new ComboBox<>();

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
        AiConfig ai = ctx.config().ai;

        CheckBox enabled = new CheckBox("Bật tính năng AI (tắt = mọi tính năng AI ngủ yên, ứng dụng vẫn chạy)");
        enabled.setSelected(ai.enabled);
        enabled.setOnAction(e -> {
            ai.enabled = enabled.isSelected();
            saveConfig();
            refreshAiStatus();
        });

        ComboBox<String> provider = new ComboBox<>();
        provider.getItems().addAll("rule-based", "ollama", "kilo");
        provider.setValue(ai.providerId);
        provider.setMaxWidth(360);
        provider.setOnAction(e -> {
            ai.providerId = provider.getValue();
            saveConfig();
            refreshAiStatus();
        });

        com.lifedesk.ui.components.FormGrid providerRow =
                new com.lifedesk.ui.components.FormGrid().row("Provider đang dùng", provider);

        // Ollama settings
        ollamaUrlField = new TextField(ai.ollama.baseUrl);
        ollamaUrlField.setOnAction(e -> {
            commitAiFields();
            saveConfig();
        });
        ollamaModelField = new TextField(ai.ollama.model);
        ollamaModelField.setPromptText("VD: qwen2.5:7b");
        ollamaModelField.setOnAction(e -> {
            ai.ollama.model = ollamaModelField.getText().trim();
            saveConfig();
        });
        Button testOllama = new Button("Kiểm tra kết nối Ollama");
        testOllama.setOnAction(e -> {
            commitAiFields();
            saveConfig();
            Fx.async(ctx.executors().ai(),
                    () -> new com.lifedesk.ai.OllamaProvider(ctx.config().ai.ollama).isAvailable(),
                    ok -> aiStatusLabel.setText(ok ? "Ollama: kết nối OK" : "Ollama: KHÔNG kết nối được (đang chạy?)"),
                    err -> aiStatusLabel.setText("Ollama lỗi: " + err.getMessage()));
        });

        Label ollamaHeading = new Label("Ollama (local)");
        ollamaHeading.getStyleClass().add("section-label");
        com.lifedesk.ui.components.FormGrid ollamaForm = new com.lifedesk.ui.components.FormGrid()
                .row("Endpoint", ollamaUrlField, testOllama)
                .row("Model", ollamaModelField);
        VBox ollamaBox = new VBox(10, ollamaHeading, ollamaForm);

        // Kilo settings
        kiloUrlField = new TextField(ai.kilo.baseUrl);
        kiloUrlField.setOnAction(e -> {
            commitAiFields();
            saveConfig();
        });
        kiloKeyField = new TextField(ai.kilo.apiKey);
        kiloKeyField.setPromptText("API key (dán JWT/key, lưu cục bộ trong config của bạn)");
        kiloKeyField.setOnAction(e -> {
            commitAiFields();
            saveConfig();
        });
        kiloModelField = new TextField(ai.kilo.model);
        kiloModelField.setOnAction(e -> {
            commitAiFields();
            saveConfig();
        });
        Button testKilo = new Button("Kiểm tra API key");
        testKilo.setOnAction(e -> {
            commitAiFields();
            saveConfig();
            Fx.async(ctx.executors().ai(),
                    () -> new com.lifedesk.ai.OpenAiCompatibleProvider(ctx.config().ai.kilo).isAvailable(),
                    ok -> aiStatusLabel.setText(ok ? "Kilo: API key OK" : "Kilo: KHÔNG truy cập được (kiểm tra key/URL)"),
                    err -> aiStatusLabel.setText("Kilo lỗi: " + err.getMessage()));
        });

        Label kiloHint = new Label("Chuẩn: https://ai-gateway.kilo.ai/api/v1 — "
                + "danh sách model lấy từ {base}/models, chat từ {base}/chat/completions.");
        kiloHint.getStyleClass().add("dim-label");
        kiloHint.setWrapText(true);
        Label kiloHeading = new Label("Kilo (cloud, OpenAI-compatible)");
        kiloHeading.getStyleClass().add("section-label");
        com.lifedesk.ui.components.FormGrid kiloForm = new com.lifedesk.ui.components.FormGrid()
                .row("Base URL", kiloUrlField, testKilo)
                .row("API key", kiloKeyField)
                .row("Model", kiloModelField);
        VBox kiloBox = new VBox(10, kiloHeading, kiloHint, kiloForm);
        installAiFieldCommits();

        // Model browser: fetching follows this picker, not the active provider, so you can
        // inspect Kilo's real catalogue while the built-in engine is selected.
        modelProviderPick.getItems().setAll("Kilo (cloud)", "Ollama (local)");
        modelProviderPick.setValue(com.lifedesk.ai.OllamaProvider.ID.equals(ai.providerId)
                ? "Ollama (local)" : "Kilo (cloud)");
        Button fetchModels = new Button("Lấy danh sách model", new IconView("download", 15));
        fetchModels.getStyleClass().add("primary-btn");
        fetchModels.setOnAction(e -> fetchModels());
        modelSearch.setPromptText("Tìm trong danh sách model…");
        modelSearch.textProperty().addListener((o, a, b) -> filterModels());
        Button selectModel = new Button("Dùng model đang chọn");
        selectModel.setOnAction(e -> selectModel());
        Button pullModel = new Button("Tải model Ollama (chỉ khi bạn yêu cầu)", new IconView("download", 15));
        pullModel.setTooltip(new javafx.scene.control.Tooltip(
                "Chỉ dùng với Ollama: nhập tên model (vd qwen2.5:7b) — ứng dụng KHÔNG tự tải."));
        pullModel.setOnAction(e -> pullModel());

        buildModelTable();
        modelTable.setPlaceholder(new Label("Bấm \"Lấy danh sách model\" để lấy từ provider đã chọn bên trái."));
        modelTable.setPrefHeight(260);
        modelTable.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(modelTable, Priority.ALWAYS);
        modelTable.setRowFactory(v -> {
            javafx.scene.control.TableRow<ModelInfo> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    selectModel();
                }
            });
            return row;
        });
        activeModelLabel.getStyleClass().add("card-headline");

        CheckBox includeData = new CheckBox("Cho phép LLM đọc tổng quan dữ liệu của tôi (tasks/tài chính/thói quen)");
        includeData.setSelected(ai.includeUserData);
        includeData.setOnAction(e -> {
            ai.includeUserData = includeData.isSelected();
            saveConfig();
        });

        HBox activeModelRow = new HBox(10, new Label("Model đang dùng:"), activeModelLabel);
        activeModelRow.setAlignment(Pos.CENTER_LEFT);
        HBox modelToolbar = new HBox(10, new Label("Lấy từ:"), modelProviderPick, fetchModels,
                modelSearch, selectModel, pullModel);
        modelToolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modelSearch, Priority.ALWAYS);

        VBox box = new VBox(14, enabled, providerRow, ollamaBox, kiloBox,
                new javafx.scene.control.Separator(), activeModelRow, modelToolbar,
                modelTable, includeData, aiStatusLabel);
        refreshAiStatus();
        return wrapScroll(box);
    }

    private void buildModelTable() {
        javafx.scene.control.TableColumn<ModelInfo, String> idCol = new javafx.scene.control.TableColumn<>("Model");
        idCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().id()));
        javafx.scene.control.TableColumn<ModelInfo, String> providerCol = new javafx.scene.control.TableColumn<>("Provider");
        providerCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().providerId()));
        javafx.scene.control.TableColumn<ModelInfo, String> infoCol = new javafx.scene.control.TableColumn<>("Thông tin");
        infoCol.setCellValueFactory(p -> new javafx.beans.property.SimpleStringProperty(p.getValue().details()));
        modelTable.getColumns().setAll(List.of(idCol, providerCol, infoCol));
        modelTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void refreshAiStatus() {
        if (!ctx.ai().enabled()) {
            aiStatusLabel.setText("AI đang TẮT.");
            activeModelLabel.setText("(tắt)");
            return;
        }
        var p = ctx.ai().currentProvider();
        String modelName = ctx.ai().activeModel();
        activeModelLabel.setText(p.map(AiProvider::id).orElse("?") + " / "
                + (modelName == null || modelName.isBlank() ? "(chưa chọn — bấm Lấy danh sách model)" : modelName));
        p.ifPresentOrElse(
                provider -> aiStatusLabel.setText("Provider: " + provider.displayName()
                        + (provider.isAvailable() ? " — sẵn sàng" : " — chưa kết nối được")),
                () -> aiStatusLabel.setText("AI tắt"));
    }

    /**
     * Text fields only committed on Enter, so a pasted key/URL never reached the
     * provider. Copy every AI field into config before any AI action runs.
     */
    private void commitAiFields() {
        var ai = ctx.config().ai;
        if (ollamaUrlField != null) {
            ai.ollama.baseUrl = ollamaUrlField.getText().trim();
        }
        if (ollamaModelField != null) {
            ai.ollama.model = ollamaModelField.getText().trim();
        }
        if (kiloUrlField != null) {
            ai.kilo.baseUrl = kiloUrlField.getText().trim();
        }
        if (kiloKeyField != null) {
            ai.kilo.apiKey = kiloKeyField.getText().trim();
        }
        if (kiloModelField != null) {
            ai.kilo.model = kiloModelField.getText().trim();
        }
    }

    private void installAiFieldCommits() {
        for (TextField f : new TextField[]{ollamaUrlField, ollamaModelField,
                kiloUrlField, kiloKeyField, kiloModelField}) {
            if (f == null) {
                continue;
            }
            f.focusedProperty().addListener((o, was, is) -> {
                if (!is) {
                    commitAiFields();
                    saveConfig();
                }
            });
        }
    }

    private void fetchModels() {
        commitAiFields();
        saveConfig();
        if (!ctx.ai().enabled()) {
            Dialogs.info("AI tắt", "Bật AI trước khi lấy danh sách model.");
            return;
        }
        String providerId = "Ollama (local)".equals(modelProviderPick.getValue())
                ? com.lifedesk.ai.OllamaProvider.ID : com.lifedesk.ai.OpenAiCompatibleProvider.ID;
        var p = ctx.ai().byId(providerId);
        if (p == null || !p.supportsModelListing()) {
            Dialogs.info("Provider không liệt kê model", "Chọn Kilo hoặc Ollama để lấy danh sách model.");
            return;
        }
        main.status("Đang lấy danh sách model từ " + p.displayName() + "…");
        Fx.async(ctx.executors().ai(), p::listModels,
                models -> {
                    fetchedModels.clear();
                    fetchedModels.addAll(models);
                    filterModels();
                    main.status(models.isEmpty()
                            ? "Provider không trả về model nào"
                            : "Đã lấy " + models.size() + " model từ " + p.displayName());
                },
                err -> Dialogs.error("Không lấy được danh sách model", err.getMessage()
                        + (providerId.equals(com.lifedesk.ai.OpenAiCompatibleProvider.ID)
                        ? "\n(Kilo: kiểm tra API key và Base URL)"
                        : "\n(Ollama: kiểm tra endpoint và đảm bảo ollama serve đang chạy)")));
    }

    /** Live filter: the table always mirrors search text over the fetched list. */
    private void filterModels() {
        String q = modelSearch.getText() == null ? "" : modelSearch.getText().trim().toLowerCase();
        modelTable.getItems().setAll(fetchedModels.stream()
                .filter(m -> q.isBlank()
                        || m.id().toLowerCase().contains(q)
                        || m.details().toLowerCase().contains(q)
                        || m.providerId().toLowerCase().contains(q))
                .toList());
    }

    private void selectModel() {
        ModelInfo selected = modelTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            Dialogs.info("Chưa chọn model", "Chọn một model trong bảng trước.");
            return;
        }
        AiConfig ai = ctx.config().ai;
        if (com.lifedesk.ai.OllamaProvider.ID.equals(selected.providerId())) {
            ai.ollama.model = selected.id();
            if (ollamaModelField != null) {
                ollamaModelField.setText(selected.id());
            }
        } else if (com.lifedesk.ai.OpenAiCompatibleProvider.ID.equals(selected.providerId())) {
            ai.kilo.model = selected.id();
            if (kiloModelField != null) {
                kiloModelField.setText(selected.id());
            }
        }
        ai.providerId = selected.providerId();
        saveConfig();
        refreshAiStatus();
        Dialogs.info("Đã chọn model", selected.id() + " sẽ được dùng cho provider " + selected.providerId());
    }

    private TextField ollamaModelField;
    private TextField ollamaUrlField;
    private TextField kiloModelField;
    private TextField kiloUrlField;
    private TextField kiloKeyField;

    private void pullModel() {
        if (!ctx.ai().enabled()) {
            Dialogs.info("AI tắt", "Bật AI trước.");
            return;
        }
        String name = Dialogs.prompt("Tải model về Ollama",
                "Tên model theo thư viện Ollama (vd: qwen2.5:7b, llama3.2:3b):", "").orElse(null);
        if (name == null || name.isBlank()) {
            return;
        }
        if (!Dialogs.confirm("Xác nhận tải model",
                "Tải \"" + name + "\" về máy qua Ollama?\nFile model có thể lớn (hàng GB). Ứng dụng không tự tải nếu bạn không bấm nút này.")) {
            return;
        }
        main.status("Đang tải model " + name + "…");
        Fx.async(ctx.executors().ai(),
                () -> new com.lifedesk.ai.OllamaProvider(ctx.config().ai.ollama).pullModel(name,
                        progress -> javafx.application.Platform.runLater(() -> main.status("Pull model: " + progress))),
                done -> {
                    Dialogs.info("Tải model xong", done + "\nBấm \"Lấy danh sách model\" để chọn model mới.");
                    fetchModels();
                },
                err -> Dialogs.error("Tải model thất bại", err.getMessage()));
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
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Cấp quyền đọc cho LifeDesk");
            java.io.File dir = chooser.showDialog(main.stage());
            if (dir != null) {
                if (!Dialogs.confirm("Cấp quyền", "Cho phép đọc file trong:\n" + dir.getAbsolutePath())) {
                    return;
                }
                ctx.config().allowedRoots.add(dir.getAbsolutePath());
                ctx.files().setAllowedRoots(ctx.config().allowedRoots);
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

    private void saveConfig() {
        Fx.async(ctx.executors().io(), () -> {
            new com.lifedesk.config.ConfigService(com.lifedesk.config.ConfigService.defaultConfigDir())
                    .save(ctx.config());
            ctx.persistAiSnapshot();
            return true;
        }, ok -> {
        }, err -> main.status("Lưu cấu hình thất bại: " + err.getMessage()));
    }

    @Override
    public void onShow() {
        refreshAiStatus();
    }
}
