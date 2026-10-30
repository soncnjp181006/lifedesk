package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.agent.AgentService;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.Card;
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
 */
public class AgentView extends View {
    private final VBox logBox = new VBox(6);
    private final TextField commandField = new TextField();
    private final Label modeLabel = new Label();
    private int logSeq = 0;

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

        // ---- workflow cards
        VBox cards = new VBox(10);
        for (AgentService.Workflow wf : ctx.agent().workflows()) {
            cards.getChildren().add(workflowCard(wf));
        }
        ScrollPane workflowScroll = new ScrollPane(cards);
        workflowScroll.setFitToWidth(true);
        workflowScroll.setStyle("-fx-background-color: transparent;");
        workflowScroll.setPrefHeight(210);

        // ---- step log
        Label logTitle = new Label("Nhật ký thao tác");
        logTitle.getStyleClass().add("section-label");
        logBox.setFillWidth(true);
        VBox logPane = new VBox(8, logTitle, logBox);
        ScrollPane logScroll = new ScrollPane(logPane);
        logScroll.setFitToWidth(true);
        logScroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(logScroll, Priority.ALWAYS);

        // ---- command bar
        commandField.setPromptText("Nói cho Agent việc cần làm, ví dụ: \"tạo task viết báo cáo hạn mai, rồi ghi chú nhắc tôi\"…");
        commandField.getStyleClass().add("search-field");
        HBox.setHgrow(commandField, Priority.ALWAYS);
        Button runBtn = new Button("Chạy lệnh", new IconView("send", 15));
        runBtn.getStyleClass().add("primary-btn");
        runBtn.setOnAction(e -> runCommand());
        commandField.setOnAction(e -> runCommand());
        HBox commandBar = new HBox(8, commandField, runBtn);
        commandBar.setAlignment(Pos.CENTER_LEFT);

        return new VBox(12, header, workflowScroll, logPane, commandBar);
    }

    private Card workflowCard(AgentService.Workflow wf) {
        Card card = new Card(wf.name());
        Label desc = new Label(wf.description());
        desc.getStyleClass().add("dim-label");
        desc.setWrapText(true);
        Button run = new Button("Chạy workflow", new IconView("check", 15));
        run.getStyleClass().add("primary-btn");
        run.setOnAction(e -> runWorkflow(wf));
        HBox row = new HBox(10, new Label(wf.steps().size() + " bước"), new Region(), run);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
        card.content(desc, row);
        return card;
    }

    private void runWorkflow(AgentService.Workflow wf) {
        log("▶ " + wf.name(), wf.description(), true);
        List<String> results = new ArrayList<>();
        ctx.agent().runWorkflowAsync(wf, ctx.executors().io(),
                update -> {
                    results.add(update.result());
                    log("Bước " + update.index() + " — " + update.label(), update.result(), update.ok());
                },
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
                answer -> {
                    log("⬥ Trả lời", answer, true);
                },
                err -> {
                    log("✖ Lỗi", err.getMessage() == null ? err.toString() : err.getMessage(), false);
                });
    }

    private void log(String headline, String detail, boolean ok) {
        Fx.runLater(() -> {
            Label h = new Label(headline);
            h.getStyleClass().add(ok ? "section-label" : "error-label");
            Label d = new Label(detail);
            d.getStyleClass().add("dim-label");
            d.setWrapText(true);
            d.setMaxWidth(Double.MAX_VALUE);
            VBox entry = new VBox(2, h, d);
            entry.getStyleClass().add("card");
            entry.getStyleClass().add(ok ? "agent-step-ok" : "agent-step-err");
            logBox.getChildren().add(entry);
            if (logBox.getChildren().size() > 100) {
                logBox.getChildren().remove(0, logBox.getChildren().size() - 100);
            }
        });
    }

    @Override
    public void onShow() {
        var provider = ctx.ai().currentProvider();
        modeLabel.setText(provider
                .map(p -> "Chế độ Agent: " + p.displayName() + (p.isAvailable() ? " (sẵn sàng)" : " (chưa kết nối)"))
                .orElse("AI đang tắt — bật trong Cài đặt → AI để Agent thao tác được."));
    }
}
