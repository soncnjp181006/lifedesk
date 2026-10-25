package com.lifedesk.ui.views;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lifedesk.app.AppContext;
import com.lifedesk.automation.AgentRun;
import com.lifedesk.automation.AgentService;
import com.lifedesk.automation.PlanStep;
import com.lifedesk.automation.RunStatus;
import com.lifedesk.automation.Tool;
import com.lifedesk.automation.Workflow;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Automation console: plan builder with pre-execution review, run history
 * with per-step records, scheduled workflows and the tool registry with
 * permissions + user-managed deny list.
 */
public class AutomationView extends View {
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final TableView<PlanStep> stepTable = new TableView<>();
    private final List<PlanStep> planSteps = new ArrayList<>();
    private final CheckBox approvePlan = new CheckBox("Tôi phê duyệt kế hoạch này (các bước ghi dữ liệu)");
    private final TableView<AgentRun> runTable = new TableView<>();
    private final TextArea stepDetail = new TextArea();
    private final ListView<Workflow> workflowList = new ListView<>();
    private final TextArea registryView = new TextArea();

    public AutomationView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "AUTOMATION";
    }

    @Override
    protected Node build() {
        Label title = new Label("Automation Agent");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label policyNote = new Label("Agent chỉ chạy qua tool registry; hành động ghi cần phê duyệt, "
                + "hành động xóa cần xác nhận tại bước chạy; mọi bước được ghi audit log.");
        policyNote.getStyleClass().add("dim-label");
        policyNote.setWrapText(true);
        HBox header = new HBox(10, title, spacer);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---------------- plan builder
        buildStepTable();
        stepTable.setPlaceholder(new EmptyState("🧩", "Thêm bước cho kế hoạch (mỗi bước = 1 tool + tham số JSON)."));
        ComboBox<String> toolPick = new ComboBox<>();
        ctx.agentService().registry().all().values().forEach(t -> toolPick.getItems().add(t.descriptor().name()));
        Button addStep = new Button("+ Thêm bước");
        addStep.setOnAction(e -> {
            PlanStep step = new PlanStep();
            step.tool = toolPick.getValue() == null ? toolPick.getItems().isEmpty() ? "" : toolPick.getItems().get(0) : toolPick.getValue();
            Tool tool = ctx.agentService().registry().find(step.tool).orElse(null);
            step.description = tool == null ? step.tool : tool.descriptor().name() + " (" + tool.descriptor().permission() + ")";
            step.params = new JsonObject();
            planSteps.add(step);
            stepTable.getItems().setAll(planSteps);
        });
        Button editStep = new Button("✎ Sửa bước");
        editStep.setOnAction(e -> editSelectedStep());
        Button delStep = new Button("🗑 Xóa bước");
        delStep.setOnAction(e -> {
            PlanStep step = stepTable.getSelectionModel().getSelectedItem();
            if (step != null) {
                planSteps.remove(step);
                stepTable.getItems().setAll(planSteps);
            }
        });
        Button runBtn = new Button("▶ Thực thi kế hoạch");
        runBtn.getStyleClass().add("primary-btn");
        runBtn.setOnAction(e -> executePlan());
        Button cancelBtn = new Button("⏹ Hủy run đang chọn");
        cancelBtn.setOnAction(e -> cancelSelectedRun());

        HBox planToolbar = new HBox(8, new Label("Tool:"), toolPick, addStep, editStep, delStep, approvePlan, runBtn);
        planToolbar.setAlignment(Pos.CENTER_LEFT);

        VBox planPane = new VBox(10, planToolbar, stepTable);
        VBox.setVgrow(stepTable, Priority.ALWAYS);

        // ---------------- runs
        buildRunTable();
        runTable.setPlaceholder(new EmptyState("🤖", "Chưa có agent run nào."));
        stepDetail.setEditable(false);
        stepDetail.setPromptText("Chọn một run để xem chi tiết từng bước…");
        VBox runsPane = new VBox(10, new HBox(8, cancelBtn), runTable, new Label("Chi tiết các bước:"), stepDetail);
        VBox.setVgrow(runTable, Priority.ALWAYS);

        // ---------------- workflows
        Button addWorkflow = new Button("+ Lưu kế hoạch thành workflow");
        addWorkflow.setOnAction(e -> saveAsWorkflow());
        Button editWorkflow = new Button("✎ Lịch / bật tắt");
        editWorkflow.setOnAction(e -> editWorkflow());
        Button runNow = new Button("▶ Chạy ngay");
        runNow.setOnAction(e -> runWorkflowNow());
        Button delWorkflow = new Button("🗑");
        delWorkflow.setOnAction(e -> deleteWorkflow());
        workflowList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Workflow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.name + " — " + scheduleLabel(item) + (item.enabled ? " [bật]" : " [tắt]"));
                }
            }
        });

        // ---------------- registry + policy
        registryView.setEditable(false);
        StringBuilder sb = new StringBuilder("Tool registry (quyền):\n\n");
        ctx.agentService().registry().all().values().forEach(t -> {
            var d = t.descriptor();
            sb.append("• ").append(d.name()).append("  [").append(d.permission()).append("]")
                    .append(ctx.security().isDenied(d.name()) ? "  — BỊ CHẶN (DENIED)" : "")
                    .append("\n  ").append(d.description()).append("\n");
        });
        sb.append("\nGiới hạn: ").append(ctx.config().agent.maxSteps).append(" bước, ")
                .append(ctx.config().agent.maxRunSeconds).append("s tổng, ")
                .append(ctx.config().agent.perStepSeconds).append("s/bước, retry tối đa ")
                .append(ctx.config().agent.maxRetries).append(".\n");
        registryView.setText(sb.toString());
        registryView.setPrefRowCount(18);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Kế hoạch", planPane),
                new Tab("Lịch sử chạy", runsPane),
                new Tab("Workflows", new VBox(10, new HBox(8, addWorkflow, editWorkflow, runNow, delWorkflow), workflowList)),
                new Tab("Tool registry & chính sách", new VBox(10, registryView, policyNote)));

        ctx.agentService().changes().addListener(() -> javafx.application.Platform.runLater(this::refreshRuns));
        return new VBox(10, header, tabs);
    }

    private static String scheduleLabel(Workflow w) {
        return switch (w.scheduleType) {
            case "DAILY" -> "hằng ngày " + w.scheduleTime.format(TIME_FMT);
            case "WEEKLY" -> "hằng tuần " + w.scheduleDay + " " + w.scheduleTime.format(TIME_FMT);
            default -> "không lịch (chạy tay)";
        };
    }

    private void buildStepTable() {
        TableColumn<PlanStep, String> toolCol = new TableColumn<>("Tool");
        toolCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().tool));
        TableColumn<PlanStep, String> descCol = new TableColumn<>("Mô tả");
        descCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().description));
        TableColumn<PlanStep, String> paramsCol = new TableColumn<>("Params JSON");
        paramsCol.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().params.toString()));
        TableColumn<PlanStep, Boolean> contCol = new TableColumn<>("Bỏ qua lỗi");
        contCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().continueOnFailure));
        stepTable.getColumns().setAll(List.of(toolCol, descCol, paramsCol, contCol));
        stepTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        stepTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editSelectedStep();
            }
        });
    }

    private void editSelectedStep() {
        PlanStep step = stepTable.getSelectionModel().getSelectedItem();
        if (step == null) {
            return;
        }
        javafx.scene.control.Dialog<PlanStep> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Sửa bước kế hoạch");
        dialog.setHeaderText(step.tool);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        TextField description = new TextField(step.description);
        TextArea params = new TextArea(step.params.toString());
        params.setPrefRowCount(6);
        params.setPromptText("JSON params theo schema của tool");
        CheckBox cont = new CheckBox("Tiếp tục các bước sau kể cả khi bước này lỗi");
        cont.setSelected(step.continueOnFailure);
        dialog.getDialogPane().setContent(new VBox(8, labeled("Mô tả", description), labeled("Params", params), cont));
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            try {
                step.params = JsonParser.parseString(params.getText().isBlank() ? "{}" : params.getText()).getAsJsonObject();
                step.description = description.getText();
                step.continueOnFailure = cont.isSelected();
                return step;
            } catch (RuntimeException e) {
                Dialogs.error("JSON không hợp lệ", e.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
        stepTable.refresh();
    }

    private void executePlan() {
        if (planSteps.isEmpty()) {
            Dialogs.info("Kế hoạch trống", "Hãy thêm ít nhất một bước.");
            return;
        }
        com.lifedesk.automation.AgentPlan plan = new com.lifedesk.automation.AgentPlan();
        plan.name = "Kế hoạch thủ công " + java.time.LocalTime.now().format(TIME_FMT);
        plan.steps = new ArrayList<>(planSteps);
        try {
            ctx.agentService().validatePlan(plan, approvePlan.isSelected());
        } catch (RuntimeException e) {
            Dialogs.warn("Kế hoạch chưa hợp lệ", e.getMessage()
                    + "\n(Chú ý: các bước ghi dữ liệu cần tick phê duyệt; bước bị chặn cần mở trong tab registry)");
            return;
        }
        if (!approvePlan.isSelected() && planRequiresApproval(plan)) {
            Dialogs.info("Cần phê duyệt", "Kế hoạch chứa bước ghi dữ liệu (WRITE). Tick ô phê duyệt để chạy.");
            return;
        }
        ctx.agentService().submitRun(plan, approvePlan.isSelected(), "manual");
        main.status("Agent run đã được gửi đi");
    }

    private boolean planRequiresApproval(com.lifedesk.automation.AgentPlan plan) {
        return plan.steps.stream().anyMatch(s -> ctx.agentService().registry().find(s.tool)
                .map(t -> t.descriptor().permission() != com.lifedesk.automation.PermissionLevel.READ)
                .orElse(true));
    }

    private void buildRunTable() {
        TableColumn<AgentRun, AgentRun> status = new TableColumn<>("Trạng thái");
        status.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        status.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(AgentRun item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : com.lifedesk.ui.components.Chips.runStatus(item.status));
            }
        });
        TableColumn<AgentRun, String> name = new TableColumn<>("Kế hoạch");
        name.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().planName));
        TableColumn<AgentRun, String> trigger = new TableColumn<>("Nguồn");
        trigger.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().trigger));
        TableColumn<AgentRun, String> note = new TableColumn<>("Ghi chú");
        note.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().statusNote == null ? "" : p.getValue().statusNote));
        TableColumn<AgentRun, String> when = new TableColumn<>("Bắt đầu");
        when.setCellValueFactory(p -> new SimpleStringProperty(p.getValue().startedAt == null ? "—"
                : p.getValue().startedAt.toString()));
        runTable.getColumns().setAll(List.of(status, name, trigger, when, note));
        runTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        runTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showRunDetail(b));
    }

    private void showRunDetail(AgentRun run) {
        if (run == null) {
            stepDetail.clear();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Run ").append(run.id).append(" — ").append(run.status).append('\n');
        if (run.awaitingStepId != null) {
            sb.append("⏳ Đang chờ phê duyệt bước: ").append(run.awaitingStepId).append('\n');
        }
        for (var s : run.steps) {
            sb.append("\n[").append(s.status).append("] ").append(s.tool)
                    .append(" (lần thử: ").append(s.attempts).append(")\n")
                    .append("  params: ").append(s.paramsSanitized).append('\n')
                    .append("  → ").append(s.message).append('\n');
        }
        stepDetail.setText(sb.toString());
    }

    private void refreshRuns() {
        runTable.getItems().setAll(ctx.agentService().runs());
        AgentRun selected = runTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
            showRunDetail(selected);
        }
    }

    private void cancelSelectedRun() {
        AgentRun run = runTable.getSelectionModel().getSelectedItem();
        if (run == null) {
            return;
        }
        ctx.agentService().cancel(run.id);
        main.status("Đã yêu cầu hủy run");
    }

    // ------------------------------------------------------------------ workflows

    private void saveAsWorkflow() {
        if (planSteps.isEmpty()) {
            Dialogs.info("Kế hoạch trống", "Hãy thêm bước trước khi lưu workflow.");
            return;
        }
        String name = Dialogs.prompt("Workflow mới", "Tên workflow:", "").orElse(null);
        if (name == null || name.isBlank()) {
            return;
        }
        Workflow w = new Workflow();
        w.name = name;
        w.steps = new ArrayList<>(planSteps);
        ctx.workflows().save(w);
        refreshWorkflows();
    }

    private void editWorkflow() {
        Workflow w = workflowList.getSelectionModel().getSelectedItem();
        if (w == null) {
            return;
        }
        javafx.scene.control.Dialog<Workflow> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Cấu hình workflow");
        dialog.setHeaderText(w.name);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        ComboBox<String> type = new ComboBox<>();
        type.getItems().addAll("NONE", "DAILY", "WEEKLY");
        type.setValue(w.scheduleType);
        ComboBox<String> day = new ComboBox<>();
        day.getItems().addAll("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
        day.setValue(w.scheduleDay == null ? "MONDAY" : w.scheduleDay.name());
        javafx.scene.control.Spinner<Integer> hour = new javafx.scene.control.Spinner<>(0, 23, w.scheduleTime.getHour());
        javafx.scene.control.Spinner<Integer> minute = new javafx.scene.control.Spinner<>(0, 59, w.scheduleTime.getMinute());
        CheckBox enabled = new CheckBox("Bật lịch");
        enabled.setSelected(w.enabled);
        CheckBox autoApprove = new CheckBox("Tự chạy không cần duyệt (chỉ hợp lệ khi toàn bộ bước là READ)");
        autoApprove.setSelected(w.autoApprove);
        dialog.getDialogPane().setContent(new VBox(8,
                new HBox(8, new Label("Lịch:"), type, day, new Label("giờ"), hour, minute),
                enabled, autoApprove));
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            w.scheduleType = type.getValue();
            w.scheduleDay = "WEEKLY".equals(type.getValue()) ? java.time.DayOfWeek.valueOf(day.getValue()) : null;
            w.scheduleTime = LocalTime.of(hour.getValue(), minute.getValue());
            w.enabled = enabled.isSelected() && !"NONE".equals(type.getValue());
            w.autoApprove = autoApprove.isSelected();
            return w;
        });
        dialog.showAndWait().ifPresent(saved -> {
            try {
                ctx.workflows().save(saved);
            } catch (RuntimeException e) {
                Dialogs.error("Không lưu được workflow", e.getMessage());
            }
            refreshWorkflows();
        });
    }

    private void runWorkflowNow() {
        Workflow w = workflowList.getSelectionModel().getSelectedItem();
        if (w == null) {
            return;
        }
        ctx.workflows().runNow(w);
        main.status("Đã gửi workflow " + w.name);
    }

    private void deleteWorkflow() {
        Workflow w = workflowList.getSelectionModel().getSelectedItem();
        if (w != null && Dialogs.confirm("Xóa workflow", "Xóa \"" + w.name + "\"?")) {
            ctx.workflows().delete(w.id);
            refreshWorkflows();
        }
    }

    private void refreshWorkflows() {
        workflowList.getItems().setAll(ctx.workflows().workflows());
    }

    @Override
    public void onShow() {
        refreshRuns();
        refreshWorkflows();
    }

    private VBox labeled(String text, Node field) {
        Label l = new Label(text);
        l.getStyleClass().add("dim-label");
        return new VBox(2, l, field);
    }
}
