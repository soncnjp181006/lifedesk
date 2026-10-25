package com.lifedesk.ui.views;

import com.lifedesk.ai.AiProvider;
import com.lifedesk.ai.ModelInfo;
import com.lifedesk.app.AppContext;
import com.lifedesk.config.AiConfig;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
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
 * Ollama pull), data directory, allowed roots, backups, agent limits.
 */
public class SettingsView extends View {
    private final TableView<ModelInfo> modelTable = new TableView<>();
    private final TextField modelSearch = new TextField();
    private final List<ModelInfo> fetchedModels = new java.util.ArrayList<>();
    private final Label aiStatusLabel = new Label("");

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
                new Tab("Agent & Nhắc việc", buildAgentTab()),
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
        box.getChildren().add(new Label(
                "Thêm theme mới: đặt file .css vào <data>/config/themes/ và khởi động lại — "
                        + "file sẽ xuất hiện trong danh sách."));
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
        provider.setOnAction(e -> {
            ai.providerId = provider.getValue();
            saveConfig();
            refreshAiStatus();
        });

        Label providerLabel = new Label("Provider đang dùng:");
        HBox providerRow = new HBox(10, providerLabel, provider);
        providerRow.setAlignment(Pos.CENTER_LEFT);

        // Ollama settings
        TextField ollamaUrl = new TextField(ai.ollama.baseUrl);
        ollamaUrl.setOnAction(e -> {
            ai.ollama.baseUrl = ollamaUrl.getText().trim();
            saveConfig();
        });
        ollamaModelField = new TextField(ai.ollama.model);
        ollamaModelField.setPromptText("VD: qwen2.5:7b");
        ollamaModelField.setOnAction(e -> {
            ai.ollama.model = ollamaModelField.getText().trim();
            saveConfig();
        });
        Button testOllama = new Button("Kiểm tra kết nối Ollama");
        testOllama.setOnAction(e -> Fx.async(ctx.executors().ai(),
                () -> new AiProvider() {
                    @Override
                    public String id() {
                        return "x";
                    }

                    @Override
                    public String displayName() {
                        return "";
                    }

                    @Override
                    public boolean isAvailable() {
                        return new com.lifedesk.ai.OllamaProvider(ai.ollama).isAvailable();
                    }

                    @Override
                    public java.util.List<ModelInfo> listModels() {
                        return List.of();
                    }

                    @Override
                    public String chat(java.util.List<com.lifedesk.ai.ChatMessage> messages, String model) {
                        return "";
                    }

                    @Override
                    public boolean supportsModelListing() {
                        return false;
                    }
                }.isAvailable(),
                ok -> aiStatusLabel.setText(ok ? "Ollama: kết nối OK" : "Ollama: KHÔNG kết nối được (đang chạy?)"),
                err -> aiStatusLabel.setText("Ollama lỗi: " + err.getMessage())));

        VBox ollamaBox = new VBox(8, new Label("Ollama (local):"),
                new HBox(8, new Label("Endpoint"), ollamaUrl, testOllama),
                new HBox(8, new Label("Model"), ollamaModelField));

        // Kilo settings
        TextField kiloUrl = new TextField(ai.kilo.baseUrl);
        kiloUrl.setOnAction(e -> {
            ai.kilo.baseUrl = kiloUrl.getText().trim();
            saveConfig();
        });
        TextField kiloKey = new TextField(ai.kilo.apiKey);
        kiloKey.setPromptText("API key (lưu cục bộ trong data/ai của bạn)");
        kiloKey.setOnAction(e -> {
            ai.kilo.apiKey = kiloKey.getText().trim();
            saveConfig();
        });
        kiloModelField = new TextField(ai.kilo.model);
        kiloModelField.setOnAction(e -> {
            ai.kilo.model = kiloModelField.getText().trim();
            saveConfig();
        });
        Button testKilo = new Button("Kiểm tra API key");
        testKilo.setOnAction(e -> Fx.async(ctx.executors().ai(),
                () -> new com.lifedesk.ai.OpenAiCompatibleProvider(ai.kilo).isAvailable(),
                ok -> aiStatusLabel.setText(ok ? "Kilo: API key OK" : "Kilo: KHÔNG truy cập được (kiểm tra key/URL)"),
                err -> aiStatusLabel.setText("Kilo lỗi: " + err.getMessage())));

        VBox kiloBox = new VBox(8, new Label("Kilo (cloud, OpenAI-compatible):"),
                new HBox(8, new Label("Base URL"), kiloUrl, testKilo),
                new HBox(8, new Label("API key"), kiloKey),
                new HBox(8, new Label("Model"), kiloModelField));

        // Model browser
        Button fetchModels = new Button("⬇ Lấy danh sách model");
        fetchModels.getStyleClass().add("primary-btn");
        fetchModels.setOnAction(e -> fetchModels());
        modelSearch.setPromptText("Tìm trong danh sách model…");
        modelSearch.textProperty().addListener((o, a, b) -> filterModels());
        Button selectModel = new Button("Dùng model đang chọn");
        selectModel.setOnAction(e -> selectModel());
        Button pullModel = new Button("⬇ Tải model Ollama (chỉ khi bạn yêu cầu)");
        pullModel.setTooltip(new javafx.scene.control.Tooltip(
                "Chỉ dùng với Ollama: nhập tên model (vd qwen2.5:7b) — ứng dụng KHÔNG tự tải."));
        pullModel.setOnAction(e -> pullModel());

        buildModelTable();
        modelTable.setPlaceholder(new Label("Bấm \"Lấy danh sách model\" để xem model từ provider hiện tại."));
        VBox.setVgrow(modelTable, Priority.ALWAYS);

        CheckBox includeData = new CheckBox("Cho phép LLM đọc tổng quan dữ liệu của tôi (tasks/tài chính/thói quen)");
        includeData.setSelected(ai.includeUserData);
        includeData.setOnAction(e -> {
            ai.includeUserData = includeData.isSelected();
            saveConfig();
        });

        VBox box = new VBox(12, enabled, providerRow, ollamaBox, kiloBox,
                new HBox(8, fetchModels, modelSearch, selectModel, pullModel),
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
            return;
        }
        var p = ctx.ai().currentProvider();
        p.ifPresentOrElse(
                provider -> aiStatusLabel.setText("Provider: " + provider.displayName()
                        + (provider.isAvailable() ? " — sẵn sàng" : " — chưa kết nối được")),
                () -> aiStatusLabel.setText("AI tắt"));
    }

    private void fetchModels() {
        if (!ctx.ai().enabled()) {
            Dialogs.info("AI tắt", "Bật AI trước khi lấy danh sách model.");
            return;
        }
        AiProvider p = ctx.ai().currentProvider().orElse(null);
        if (p == null || !p.supportsModelListing()) {
            Dialogs.info("Provider không liệt kê model", "Engine quy tắc không có danh sách model. Chọn Ollama hoặc Kilo.");
            return;
        }
        main.status("Đang lấy danh sách model từ " + p.displayName() + "…");
        Fx.async(ctx.executors().ai(), p::listModels,
                models -> {
                    fetchedModels.clear();
                    fetchedModels.addAll(models);
                    filterModels();
                    main.status("Đã lấy " + models.size() + " model từ " + p.displayName());
                },
                err -> {
                    Dialogs.error("Không lấy được danh sách model", err.getMessage()
                            + "\n(Kilo: kiểm tra API key; Ollama: kiểm tra endpoint và đảm bảo ollama serve đang chạy)");
                });
    }

    private void filterModels() {
        String q = modelSearch.getText() == null ? "" : modelSearch.getText().trim().toLowerCase();
        fetchedModels.stream()
                .filter(m -> q.isBlank() || m.id().toLowerCase().contains(q) || m.details().toLowerCase().contains(q))
                .toList()
                .forEach(m -> {
                    if (!modelTable.getItems().contains(m)) {
                        modelTable.getItems().add(m);
                    }
                });
        if (!q.isBlank()) {
            modelTable.getItems().removeIf(m -> !m.id().toLowerCase().contains(q) && !m.details().toLowerCase().contains(q));
        } else if (modelTable.getItems().size() != fetchedModels.size()) {
            modelTable.getItems().setAll(fetchedModels);
        }
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
    private TextField kiloModelField;

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

        Button backup = new Button("💾 Tạo bản sao lưu ngay");
        backup.getStyleClass().add("primary-btn");
        backup.setOnAction(e -> Fx.async(ctx.executors().io(), () -> ctx.backup().backup(ctx.dataDir(),
                        ctx.dataDir().resolve("backups")),
                zip -> Dialogs.info("Sao lưu xong", "Đã tạo: " + zip),
                err -> Dialogs.error("Sao lưu thất bại", err.getMessage())));

        Button restore = new Button("♻ Khôi phục từ bản sao lưu");
        restore.setOnAction(e -> restoreBackup());

        Button integrity = new Button("🧪 Kiểm tra toàn vẹn dữ liệu");
        integrity.setOnAction(e -> Fx.async(ctx.executors().io(),
                () -> ctx.backup().integrityCheck(ctx.dataDir()),
                corrupt -> Dialogs.info("Kiểm tra toàn vẹn", corrupt.isEmpty()
                        ? "Tất cả file JSON đều đọc tốt. ✓"
                        : "File hỏng:\n- " + String.join("\n- ", corrupt)),
                err -> Dialogs.error("Lỗi kiểm tra", err.getMessage())));

        Button openData = new Button("📂 Mở thư mục dữ liệu");
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
        Button add = new Button("+ Cấp quyền thư mục");
        add.getStyleClass().add("primary-btn");
        add.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Cấp quyền đọc cho LifeDesk + agent");
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
        VBox box = new VBox(10, new Label("Các thư mục được cấp quyền (file manager + agent file.search):"),
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

    // ------------------------------------------------------------------ agent & reminders

    private Node buildAgentTab() {
        var limits = ctx.config().agent;
        Spinner<Integer> maxSteps = new Spinner<>(1, 100, limits.maxSteps);
        maxSteps.valueProperty().addListener((o, a, b) -> { limits.maxSteps = maxSteps.getValue();
            saveConfig();
        });
        Spinner<Integer> runSeconds = new Spinner<>(10, 3600, limits.maxRunSeconds, 10);
        runSeconds.valueProperty().addListener((o, a, b) -> { limits.maxRunSeconds = runSeconds.getValue();
            saveConfig();
        });
        Spinner<Integer> stepSeconds = new Spinner<>(1, 600, limits.perStepSeconds, 5);
        stepSeconds.valueProperty().addListener((o, a, b) -> { limits.perStepSeconds = stepSeconds.getValue();
            saveConfig();
        });
        Spinner<Integer> retries = new Spinner<>(0, 5, limits.maxRetries);
        retries.valueProperty().addListener((o, a, b) -> { limits.maxRetries = retries.getValue();
            saveConfig();
        });

        VBox toolList = new VBox(6);
        for (var tool : ctx.agentService().registry().all().values()) {
            var d = tool.descriptor();
            CheckBox cb = new CheckBox(d.name() + "  [" + d.permission() + "]"
                    + (d.permission() != com.lifedesk.automation.PermissionLevel.READ ? " — cần phê duyệt/xác nhận" : ""));
            cb.setSelected(!ctx.security().isDenied(d.name()));
            cb.setOnAction(e -> {
                ctx.security().setDenied(d.name(), !cb.isSelected());
                if (!cb.isSelected()) {
                    Dialogs.info("Tool bị chặn", d.name() + " sẽ bị từ chối trong mọi kế hoạch.");
                }
            });
            toolList.getChildren().add(cb);
        }

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
                new Label("Giới hạn agent (chống vòng lặp vô hạn):"),
                new HBox(10, new Label("Số bước tối đa"), maxSteps),
                new HBox(10, new Label("Tổng thời gian (giây)"), runSeconds),
                new HBox(10, new Label("Timeout mỗi bước (giây)"), stepSeconds),
                new HBox(10, new Label("Số lần retry"), retries),
                new Label("Công cụ của agent — bỏ tick để CHẶN tool đó hoàn toàn:"),
                toolList,
                new Label("Nhắc việc:"),
                reminders, new HBox(8, new Label("Nhắc trước (phút)"), minutes));
        return wrapScroll(box);
    }

    // ------------------------------------------------------------------ about

    private Node buildAboutTab() {
        VBox box = new VBox(10,
                new Label("LifeDesk 1.0.0"),
                new Label("Ứng dụng năng suất cá nhân offline-first: công việc, ghi chú markdown, tài chính, "
                        + "thói quen, sức khỏe, học tập, tệp, tìm kiếm, AI cục bộ và automation agent."),
                new Label("Dữ liệu: JSON + Markdown thuần trong thư mục dữ liệu của bạn — không database server, không cloud."),
                new Label("Sức khỏe: chỉ ghi nhận thủ công, không chẩn đoán y tế."),
                new Label("Agent: chỉ hoạt động qua tool registry có phân quyền + audit log; không thực thi shell."));
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
