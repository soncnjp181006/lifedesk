package com.lifedesk.ui.views;

import com.lifedesk.agent.AgentService;
import com.lifedesk.app.AppContext;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.IconView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent Workflow: the AI operates the app through a tool registry —
 * deterministic workflow cards (run a fixed step sequence that ends in a
 * report note) plus a natural-language command bar where an LLM picks tools
 * itself. Every step is logged here, nothing happens silently.
 *
 * <p>Layout note: the workflow list keeps its natural height. It used to live in
 * a scroll pane pinned to 210px, which clipped the second card and let the log
 * heading draw over it.
 */
public class AgentView extends View {
    private final VBox logBox = new VBox(6);
    private final TextField commandField = new TextField();
    private final Label modeLabel = new Label();
    private final Label logCount = new Label();
    private final Label logEmpty = new Label("Chưa chạy thao tác nào. Chọn một workflow hoặc ra lệnh cho Agent.");
    private ScrollPane logScroll;
    private int entryCount = 0;

    public AgentView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "AGENT";
    }

    @Override
    protected Node build() {
        Label title = new Label("Agent Workflow");
        title.getStyleClass().add("title-label");
        modeLabel.getStyleClass().add("dim-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, title, spacer, modeLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- workflow cards (natural height, no clipping)
        Label sectionLabel = new Label("Workflow có sẵn");
        sectionLabel.getStyleClass().add("overline");
        VBox cards = new VBox(10);
        for (AgentService.Workflow wf : ctx.agent().workflows()) {
            cards.getChildren().add(workflowCard(wf));
        }

        // ---- step log inside a panel that owns the remaining height
        Label logTitle = new Label("Nhật ký thao tác");
        logTitle.getStyleClass().add("section-label");
        logCount.getStyleClass().add("chip");
        logCount.setVisible(false);
        logCount.setManaged(false);
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        Button clearLog = new Button("", new IconView("trash", 14));
        clearLog.getStyleClass().add("icon-btn");
        clearLog.setTooltip(new Tooltip("Xóa nhật ký"));
        clearLog.setOnAction(e -> {
            logBox.getChildren().clear();
            entryCount = 0;
            logEmpty.setVisible(true);
            logEmpty.setManaged(true);
            updateCount();
        });
        HBox logHead = new HBox(8, new IconView("clipboard-list", 15, "icon-dim"), logTitle,
                logCount, headSpacer, clearLog);
        logHead.setAlignment(Pos.CENTER_LEFT);
        logHead.getStyleClass().add("panel-head");

        logEmpty.getStyleClass().add("dim-label");
        logEmpty.setWrapText(true);
        logBox.setFillWidth(true);
        VBox logContent = new VBox(6, logEmpty, logBox);
        logScroll = new ScrollPane(logContent);
        logScroll.setFitToWidth(true);
        logScroll.setMinHeight(120);
        logScroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(logScroll, Priority.ALWAYS);

        VBox logPanel = new VBox(8, logHead, logScroll);
        logPanel.getStyleClass().add("panel");
        VBox.setVgrow(logPanel, Priority.ALWAYS);

        // ---- command bar
        commandField.setPromptText("Nói cho Agent việc cần làm, ví dụ: \"tạo task viết báo cáo hạn mai, rồi ghi chú nhắc tôi\"…");
        commandField.getStyleClass().add("search-field");
        commandField.setOnAction(e -> runCommand());
        HBox.setHgrow(commandField, Priority.ALWAYS);
        Button runBtn = new Button("Chạy lệnh", new IconView("send", 15));
        runBtn.getStyleClass().add("primary-btn");
        runBtn.setOnAction(e -> runCommand());
        HBox commandBar = new HBox(8, commandField, runBtn);
        commandBar.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Agent chỉ thao tác trên dữ liệu của bạn và ghi lại từng bước ở trên.");
        hint.getStyleClass().add("chat-hint");

        VBox commandPanel = new VBox(8, commandBar, hint);
        commandPanel.getStyleClass().add("panel");

        VBox root = new VBox(12, header, sectionLabel, cards, logPanel, commandPanel);
        VBox.setVgrow(logPanel, Priority.ALWAYS);
        return root;
    }

    private VBox workflowCard(AgentService.Workflow wf) {
        Label name = new Label(wf.name());
        name.getStyleClass().add("card-title");
        Label desc = new Label(wf.description());
        desc.getStyleClass().add("dim-label");
        desc.setWrapText(true);

        Label stepCount = new Label(wf.steps().size() + " bước");
        stepCount.getStyleClass().add("chip");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button run = new Button("Chạy workflow", new IconView("check", 14));
        run.getStyleClass().add("primary-btn");
        run.setOnAction(e -> runWorkflow(wf));
        HBox actions = new HBox(10, stepCount, spacer, run);
        actions.setAlignment(Pos.CENTER_LEFT);

        StringBuilder stepsText = new StringBuilder();
        for (AgentService.WorkflowStep step : wf.steps()) {
            if (stepsText.length() > 0) {
                stepsText.append("  ·  ");
            }
            stepsText.append(step.label());
        }
        Label steps = new Label(stepsText.toString());
        steps.getStyleClass().add("chat-hint");
        steps.setWrapText(true);

        VBox card = new VBox(6, name, desc, steps, actions);
        card.getStyleClass().add("card");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private void runWorkflow(AgentService.Workflow wf) {
        log("▶ " + wf.name(), wf.description(), true);
        ctx.agent().runWorkflowAsync(wf, ctx.executors().io(),
                update -> log("Bước " + update.index() + " — " + update.label(), update.result(), update.ok()),
                () -> {
                    log("✔ Hoàn tất " + wf.name(), "Xem ghi chú kết quả trong module Ghi chú.", true);
                    main.status("Agent: đã chạy " + wf.name());
                });
    }

    private void runCommand() {
        String instruction = commandField.getText();
        if (instruction == null || instruction.isBlank()) {
            return;
        }
        commandField.clear();
        log("▶ Lệnh", instruction, true);
        ctx.agent().runCommandAsync(instruction, ctx.executors().ai(),
                update -> log(update.label(), update.result(), update.ok()),
                answer -> log("⬥ Trả lời", answer, true),
                err -> log("✖ Lỗi", err.getMessage() == null ? err.toString() : err.getMessage(), false));
    }

    /** One compact log row: status dot + headline + wrapped detail. */
    private void log(String headline, String detail, boolean ok) {
        Fx.runLater(() -> {
            logEmpty.setVisible(false);
            logEmpty.setManaged(false);
            Label dot = new Label(ok ? "●" : "✖");
            dot.getStyleClass().add(ok ? "icon-ok" : "icon-danger");
            dot.setMinWidth(14);
            Label h = new Label(headline);
            h.getStyleClass().add(ok ? "section-label" : "chat-error");
            Label d = new Label(detail == null ? "" : detail);
            d.getStyleClass().add("dim-label");
            d.setWrapText(true);
            d.setMaxWidth(Double.MAX_VALUE);
            VBox text = new VBox(1, h, d);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox entry = new HBox(8, dot, text);
            entry.setAlignment(Pos.TOP_LEFT);
            entry.getStyleClass().add("log-row");
            entry.getStyleClass().add(ok ? "log-row-ok" : "log-row-err");
            logBox.getChildren().add(entry);
            entryCount++;
            if (logBox.getChildren().size() > 100) {
                logBox.getChildren().remove(0, logBox.getChildren().size() - 100);
            }
            updateCount();
            javafx.application.Platform.runLater(() -> logScroll.setVvalue(1.0));
        });
    }

    private void updateCount() {
        logCount.setText(entryCount + "");
        logCount.setVisible(entryCount > 0);
        logCount.setManaged(entryCount > 0);
    }

    @Override
    public void onShow() {
        var provider = ctx.ai().currentProvider();
        modeLabel.setText(provider
                .map(p -> "Agent: " + p.displayName())
                .orElse("AI đang tắt — bật trong Cài đặt → AI để Agent thao tác được."));
    }
}
