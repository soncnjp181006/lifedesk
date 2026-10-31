package com.lifedesk.ui.panes;

import com.lifedesk.ai.AiProvider;
import com.lifedesk.ai.ModelInfo;
import com.lifedesk.app.AppContext;
import com.lifedesk.config.AiConfig;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.components.Card;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.components.FormGrid;
import com.lifedesk.ui.components.IconView;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * The AI tab of the settings screen: enable/disable, provider selection,
 * Ollama + Kilo connection fields, model browser (fetch / filter / select /
 * pull) and data-sharing consent. Extracted from SettingsView so AI provider
 * management evolves independently of the other settings tabs.
 */
public class AiSettingsPane extends VBox {
    private final AppContext ctx;
    private final MainWindow main;
    /** Persists app config after every committed change (SettingsView.saveConfig). */
    private final Runnable configSaver;

    private final TableView<ModelInfo> modelTable = new TableView<>();
    private final TextField modelSearch = new TextField();
    private final List<ModelInfo> fetchedModels = new java.util.ArrayList<>();
    private final Label aiStatusLabel = new Label("");
    private final Label activeModelLabel = new Label();
    private final ComboBox<String> modelProviderPick = new ComboBox<>();

    private TextField ollamaModelField;
    private TextField ollamaUrlField;
    private TextField kiloModelField;
    private TextField kiloUrlField;
    private TextField kiloKeyField;

    public AiSettingsPane(AppContext ctx, MainWindow main, Runnable configSaver) {
        this.ctx = ctx;
        this.main = main;
        this.configSaver = configSaver;
        setSpacing(14);
        getChildren().setAll(buildContent());
    }

    /** Re-read provider state into the status labels (call when the tab shows). */
    public void refreshStatus() {
        refreshAiStatus();
    }

    private VBox buildContent() {
        AiConfig ai = ctx.config().ai;

        CheckBox enabled = new CheckBox("Bật tính năng AI (tắt = mọi tính năng AI ngủ yên, ứng dụng vẫn chạy)");
        enabled.setSelected(ai.enabled);
        enabled.setOnAction(e -> {
            ai.enabled = enabled.isSelected();
            configSaver.run();
            refreshAiStatus();
        });

        ComboBox<String> provider = new ComboBox<>();
        provider.getItems().addAll("rule-based", "ollama", "kilo");
        provider.setValue(ai.providerId);
        provider.setMaxWidth(360);
        provider.setOnAction(e -> {
            ai.providerId = provider.getValue();
            configSaver.run();
            refreshAiStatus();
        });

        FormGrid providerRow = new FormGrid().row("Provider đang dùng", provider);

        // Ollama settings
        ollamaUrlField = new TextField(ai.ollama.baseUrl);
        ollamaUrlField.setOnAction(e -> {
            commitAiFields();
            configSaver.run();
        });
        ollamaModelField = new TextField(ai.ollama.model);
        ollamaModelField.setPromptText("VD: qwen2.5:7b");
        ollamaModelField.setOnAction(e -> {
            ai.ollama.model = ollamaModelField.getText().trim();
            configSaver.run();
        });
        Button testOllama = new Button("Kiểm tra kết nối Ollama");
        testOllama.setOnAction(e -> {
            commitAiFields();
            configSaver.run();
            Fx.async(ctx.executors().ai(),
                    () -> new com.lifedesk.ai.OllamaProvider(ctx.config().ai.ollama).isAvailable(),
                    ok -> aiStatusLabel.setText(ok ? "Ollama: kết nối OK" : "Ollama: KHÔNG kết nối được (đang chạy?)"),
                    err -> aiStatusLabel.setText("Ollama lỗi: " + err.getMessage()));
        });

        Label ollamaHeading = new Label("Ollama (local)");
        ollamaHeading.getStyleClass().add("section-label");
        FormGrid ollamaForm = new FormGrid()
                .row("Endpoint", ollamaUrlField, testOllama)
                .row("Model", ollamaModelField);
        VBox ollamaBox = new VBox(10, ollamaHeading, ollamaForm);

        // Kilo settings
        kiloUrlField = new TextField(ai.kilo.baseUrl);
        kiloUrlField.setOnAction(e -> {
            commitAiFields();
            configSaver.run();
        });
        kiloKeyField = new TextField(ai.kilo.apiKey);
        kiloKeyField.setPromptText("API key (dán JWT/key, lưu cục bộ trong config của bạn)");
        kiloKeyField.setOnAction(e -> {
            commitAiFields();
            configSaver.run();
        });
        kiloModelField = new TextField(ai.kilo.model);
        kiloModelField.setOnAction(e -> {
            commitAiFields();
            configSaver.run();
        });
        Button testKilo = new Button("Kiểm tra API key");
        testKilo.setOnAction(e -> {
            commitAiFields();
            configSaver.run();
            Fx.async(ctx.executors().ai(),
                    () -> new com.lifedesk.ai.OpenAiCompatibleProvider(ctx.config().ai.kilo).isAvailable(),
                    ok -> aiStatusLabel.setText(ok ? "Kilo: API key OK" : "Kilo: KHÔNG truy cập được (kiểm tra key/URL)"),
                    err -> aiStatusLabel.setText("Kilo lỗi: " + err.getMessage()));
        });

        Label kiloHint = Card.dim("Chuẩn: https://ai-gateway.kilo.ai/api/v1 — "
                + "danh sách model lấy từ {base}/models, chat từ {base}/chat/completions.");
        kiloHint.setWrapText(true);
        Label kiloHeading = new Label("Kilo (cloud, OpenAI-compatible)");
        kiloHeading.getStyleClass().add("section-label");
        FormGrid kiloForm = new FormGrid()
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
        Fx.debounced(modelSearch, this::filterModels);
        Button selectModel = new Button("Dùng model đang chọn");
        selectModel.setOnAction(e -> selectModel());
        Button pullModel = new Button("Tải model Ollama", new IconView("download", 15));
        pullModel.setTooltip(new Tooltip(
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
            configSaver.run();
        });

        HBox activeModelRow = new HBox(10, new Label("Model đang dùng:"), activeModelLabel);
        activeModelRow.setAlignment(Pos.CENTER_LEFT);
        HBox modelToolbar = new HBox(10, new Label("Lấy từ:"), modelProviderPick, fetchModels,
                modelSearch, selectModel, pullModel);
        modelToolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(modelSearch, Priority.ALWAYS);
        // The three buttons used to lose letters first ("Lấy danh s…"); the search
        // field is the one that may get short.
        Fx.pinFixedWidths(modelToolbar, modelSearch);

        VBox box = new VBox(14, enabled, providerRow, ollamaBox, kiloBox,
                new Separator(), activeModelRow, modelToolbar,
                modelTable, includeData, aiStatusLabel);
        refreshAiStatus();
        return box;
    }

    private void buildModelTable() {
        TableColumn<ModelInfo, String> idCol = new TableColumn<>("Model");
        idCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().id()));
        TableColumn<ModelInfo, String> providerCol = new TableColumn<>("Provider");
        providerCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().providerId()));
        TableColumn<ModelInfo, String> infoCol = new TableColumn<>("Thông tin");
        infoCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().details()));
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
                    configSaver.run();
                }
            });
        }
    }

    private void fetchModels() {
        commitAiFields();
        configSaver.run();
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
        configSaver.run();
        refreshAiStatus();
        Dialogs.info("Đã chọn model", selected.id() + " sẽ được dùng cho provider " + selected.providerId());
    }

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
}
