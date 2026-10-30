package com.lifedesk.ui.views;

import com.lifedesk.ai.AiProvider;
import com.lifedesk.app.AppContext;
import com.lifedesk.markdown.MarkdownPreviewPane;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.IconView;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI chat with realtime streaming: the assistant reply streams token-by-token
 * and renders live as markdown + LaTeX (no chat-bubble wrapper — output is a
 * proper rendered document). Supports Smart-Composer-style context: "@"
 * attaches notes/app data as context chips, "/" opens prompt templates.
 */
public class AiChatView extends View {
    private final List<com.lifedesk.ai.ChatMessage> history = new ArrayList<>();
    /** Context items attached via "@" (smart-composer style). */
    private final List<ContextItem> contextItems = new ArrayList<>();
    private final VBox chatBox = new VBox(10);
    private final HBox contextBar = new HBox(6);
    private final TextArea input = new TextArea();
    private final Label providerStatus = new Label();
    private final PauseTransition renderThrottle = new PauseTransition(Duration.millis(200));
    private Node emptyState;
    private Button sendBtn;
    private Button stopBtn;
    /** Bumps on stop/new-chat so a late provider reply cannot touch the UI. */
    private int chatGen = 0;
    private volatile boolean busy = false;
    /** Guards against a slow probe from an earlier refresh overwriting a newer one. */
    private int statusTicket = 0;
    private Popup picker;
    /** Start index of the @-token being typed (for replacing it on selection). */
    private int atTokenStart = -1;

    /** One attached context: an app-data summary or a full note. */
    private record ContextItem(String label, String content) {
    }

    public AiChatView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "AI";
    }

    @Override
    protected Node build() {
        Label title = new Label("Trợ lý AI");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button newChatBtn = new Button("Hội thoại mới", new IconView("plus", 15));
        newChatBtn.setOnAction(e -> startNewChat());
        Button settingsBtn = new Button("Cài đặt AI", new IconView("settings", 15));
        settingsBtn.setOnAction(e -> main.showView(MainWindow.ViewId.SETTINGS));
        HBox header = new HBox(10, title, spacer, providerStatus, newChatBtn, settingsBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox quickRow = new HBox(8);
        for (String quick : List.of("việc hôm nay", "quá hạn", "gợi ý", "chi tiêu tháng này",
                "thói quen", "ôn tập", "sức khỏe")) {
            Button b = new Button(capitalizeFirst(quick));
            b.getStyleClass().add("ghost-btn");
            b.setOnAction(e -> {
                input.setText(quick);
                send();
            });
            quickRow.getChildren().add(b);
        }

        // ---- context chips (@-mentions)
        contextBar.setVisible(false);
        contextBar.setManaged(false);
        Label contextHint = new Label("Ngữ cảnh đính kèm:");
        contextHint.getStyleClass().add("dim-label");
        Button clearContext = new Button("Gỡ hết", new IconView("circle-x", 13));
        clearContext.getStyleClass().add("ghost-btn");
        clearContext.setOnAction(e -> {
            contextItems.clear();
            refreshContextBar();
        });
        HBox contextHeader = new HBox(8, contextHint, new Region(), clearContext);
        HBox.setHgrow(contextHeader.getChildren().get(1), Priority.ALWAYS);
        VBox contextArea = new VBox(4, contextHeader, contextBar);

        // ---- chat area
        chatBox.setFillWidth(true);
        ScrollPane chatScroll = new ScrollPane(chatBox);
        chatScroll.setFitToWidth(true);
        chatScroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        chatScroll.setPrefHeight(1000);

        // ---- input
        input.setPromptText("Hỏi AI… (@ gắn ngữ cảnh, / dùng mẫu lệnh, Enter để gửi, Shift+Enter xuống dòng)");
        input.getStyleClass().add("notes-editor");
        input.setPrefRowCount(3);
        input.setWrapText(true);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                send();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                hidePicker();
            }
        });
        input.textProperty().addListener((o, old, now) -> onInputChanged(now));
        sendBtn = new Button("Gửi", new IconView("send", 15));
        sendBtn.getStyleClass().add("primary-btn");
        sendBtn.setOnAction(e -> send());
        stopBtn = new Button("Dừng", new IconView("circle-x", 15));
        stopBtn.getStyleClass().add("danger-btn");
        stopBtn.setOnAction(e -> stopChat());
        HBox inputRow = new HBox(8, input, new VBox(6, sendBtn, stopBtn));
        inputRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);

        // Throttled live re-render while streaming.
        renderThrottle.setOnFinished(e -> {
            // no-op; render target is captured per-exchange
        });

        Label disclaimer = new Label("AI chạy theo provider bạn cấu hình (offline hoặc cloud). "
                + "Câu trả lời hiển thị dạng markdown + LaTeX; bật \"đọc dữ liệu\" trong Cài đặt để AI dựa trên dữ liệu thật.");
        disclaimer.getStyleClass().add("dim-label");
        disclaimer.setWrapText(true);

        VBox root = new VBox(10, header, quickRow, chatScroll, contextArea, inputRow, disclaimer);
        if (history.isEmpty()) {
            emptyState = new EmptyState("bot",
                    "Chưa có hội thoại. @ để gắn ghi chú/dữ liệu, / để dùng mẫu lệnh.");
            chatBox.getChildren().add(emptyState);
        }
        refreshStatus();
        setBusy(false);
        return root;
    }

    // ------------------------------------------------------------------ input helpers

    private void onInputChanged(String now) {
        if (now == null || now.isEmpty()) {
            hidePicker();
            return;
        }
        int caret = input.getCaretPosition();
        if (caret > 0 && now.charAt(Math.min(caret, now.length()) - 1) == '@') {
            atTokenStart = caret - 1;
            showContextPicker();
            return;
        }
        if (atTokenStart >= 0 && (caret < atTokenStart + 1 || !now.startsWith("@", atTokenStart))) {
            atTokenStart = -1;
        }
        if (now.startsWith("/")) {
            showTemplatePicker();
        } else if (picker != null && picker.isShowing() && atTokenStart < 0) {
            hidePicker();
        }
    }

    /** Context picker: app data summaries + every note (smart-composer "@"). */
    private void showContextPicker() {
        List<ContextItem> options = new ArrayList<>();
        options.add(new ContextItem("⌘ Tổng quan công việc", taskOverview()));
        options.add(new ContextItem("⌘ Tài chính tháng này", financeOverview()));
        options.add(new ContextItem("⌘ Thói quen + mục tiêu", habitOverview()));
        for (var n : ctx.notes().list()) {
            options.add(new ContextItem("📄 " + n.title, ctx.notes().readContent(n.id)));
        }
        openPicker(options, item -> {
            contextItems.add(item);
            refreshContextBar();
            if (atTokenStart >= 0) {
                String text = input.getText();
                int end = atTokenStart + 1;
                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                input.replaceText(atTokenStart, Math.min(end, input.getCaretPosition()), "");
                atTokenStart = -1;
            }
            hidePicker();
        });
    }

    private void showTemplatePicker() {
        List<ContextItem> options = new ArrayList<>();
        for (var t : com.lifedesk.ai.AiManager.promptTemplates()) {
            options.add(new ContextItem(t.name(), t.body()));
        }
        openPicker(options, item -> {
            input.setText(item.content());
            input.positionCaret(item.content().length());
            hidePicker();
        });
    }

    private void openPicker(List<ContextItem> options, Consumer<ContextItem> onSelect) {
        hidePicker();
        ListView<ContextItem> list = new ListView<>();
        list.getItems().setAll(options);
        list.setPrefHeight(Math.min(320, options.size() * 34 + 12));
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(ContextItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        list.setOnMouseClicked(e -> {
            if (list.getSelectionModel().getSelectedItem() != null) {
                onSelect.accept(list.getSelectionModel().getSelectedItem());
            }
        });
        list.setPrefWidth(340);
        VBox pane = new VBox(list);
        pane.getStyleClass().add("notif-popup");
        picker = new Popup();
        picker.setAutoHide(true);
        picker.getContent().setAll(pane);
        picker.show(input, input.localToScreen(0, 0).getX(), input.localToScreen(0, 0).getY() - pickerHeight(options));
    }

    private double pickerHeight(List<ContextItem> options) {
        return Math.min(320, options.size() * 34 + 12) + 10;
    }

    private void hidePicker() {
        if (picker != null && picker.isShowing()) {
            picker.hide();
        }
        picker = null;
    }

    private void refreshContextBar() {
        contextBar.getChildren().clear();
        boolean any = !contextItems.isEmpty();
        contextBar.setVisible(any);
        contextBar.setManaged(any);
        for (ContextItem item : contextItems) {
            Label chip = new Label(item.label());
            chip.getStyleClass().add("chip");
            chip.setMaxWidth(260);
            contextBar.getChildren().add(chip);
        }
    }

    private String taskOverview() {
        return "Việc hôm nay:\n" + taskList("today") + "\nQuá hạn:\n" + taskList("overdue");
    }

    private String taskList(String filter) {
        var tasks = "today".equals(filter) ? ctx.tasks().dueToday() : ctx.tasks().overdue();
        if (tasks.isEmpty()) {
            return "(không có)";
        }
        StringBuilder sb = new StringBuilder();
        for (var t : tasks) {
            sb.append("- ").append(t.title).append(t.dueDate != null ? " (hạn " + t.dueDate + ")" : "").append('\n');
        }
        return sb.toString();
    }

    private String financeOverview() {
        var s = ctx.finance().monthSummary(java.time.YearMonth.now());
        return "Thu " + s.income().toPlainString() + " VND, chi " + s.expense().toPlainString()
                + " VND, ròng " + s.net().toPlainString() + " VND (tháng " + java.time.YearMonth.now() + ")";
    }

    private String habitOverview() {
        StringBuilder sb = new StringBuilder();
        for (var h : ctx.habits().habits(false)) {
            sb.append("- ").append(h.name).append(" (chuỗi ").append(ctx.habits().currentStreak(h)).append(" ngày)\n");
        }
        for (var g : ctx.habits().goals()) {
            sb.append("- [Mục tiêu] ").append(g.title()).append(" — ").append(g.status()).append('\n');
        }
        return sb.isEmpty() ? "(không có)" : sb.toString();
    }

    // ------------------------------------------------------------------ chat flow

    private void refreshStatus() {
        if (!ctx.ai().enabled()) {
            providerStatus.setText("AI: ĐANG TẮT (bật trong Cài đặt)");
            providerStatus.setGraphic(null);
            chatBox.getChildren().setAll(new EmptyState("bot",
                    "Tính năng AI đang tắt trong Cài đặt → AI. Các module khác vẫn hoạt động bình thường.",
                    settingsButton()));
            return;
        }
        var provider = ctx.ai().currentProvider();
        if (provider.isEmpty()) {
            providerStatus.setGraphic(null);
            providerStatus.setText("AI: tắt");
            return;
        }
        AiProvider p = provider.get();
        renderStatus(p, null);
        final int ticket = ++statusTicket;
        Thread probe = new Thread(() -> {
            boolean probed;
            try {
                probed = p.isAvailable();
            } catch (RuntimeException e) {
                probed = false;
            }
            final boolean online = probed;
            Platform.runLater(() -> {
                if (ticket == statusTicket) {
                    renderStatus(p, online);
                }
            });
        }, "lifedesk-ai-status");
        probe.setDaemon(true);
        probe.start();
    }

    private void renderStatus(AiProvider p, Boolean online) {
        providerStatus.getStyleClass().add("dim-label");
        providerStatus.setText("Provider: " + p.displayName()
                + (online == null ? "  (đang kiểm tra)" : online ? "  sẵn sàng" : "  không kết nối được"));
        providerStatus.setGraphic(online == null
                ? new IconView("hourglass", 13, "icon-dim")
                : new IconView(online ? "circle-check" : "triangle-alert", 13,
                        online ? "icon-ok" : "icon-danger"));
    }

    private Button settingsButton() {
        Button b = new Button("Mở Cài đặt AI");
        b.getStyleClass().add("primary-btn");
        b.setOnAction(e -> main.showView(MainWindow.ViewId.SETTINGS));
        return b;
    }

    private void setBusy(boolean running) {
        busy = running;
        if (sendBtn != null) {
            sendBtn.setDisable(running);
        }
        if (stopBtn != null) {
            stopBtn.setDisable(!running);
        }
    }

    private void send() {
        String text = input.getText();
        if (text == null || text.isBlank() || busy) {
            return;
        }
        if (!ctx.ai().enabled()) {
            Dialogs.info("AI đang tắt", "Bật AI và chọn provider trong Cài đặt → AI.");
            return;
        }
        var provider = ctx.ai().currentProvider();
        if (provider.isEmpty()) {
            Dialogs.info("AI tắt", "Chưa có provider nào đang bật.");
            return;
        }
        final int gen = ++chatGen;
        final String question = text.trim();
        setBusy(true);
        input.clear();
        hidePicker();

        addUserRow(question);
        // Rendered streaming reply (no bubble — output is a live markdown doc).
        StringBuilder streaming = new StringBuilder();
        MarkdownPreviewPane replyPane = new MarkdownPreviewPane();
        replyPane.render("", ctx.themeManager().previewPalette(ctx.config().theme));
        VBox replyCard = new VBox(replyPane);
        replyCard.getStyleClass().add("card");
        replyCard.setMaxWidth(Double.MAX_VALUE);
        chatBox.getChildren().remove(emptyState);
        chatBox.getChildren().add(replyCard);
        Label liveLabel = new Label("…");
        liveLabel.getStyleClass().add("dim-label");
        replyCard.getChildren().add(liveLabel);

        List<com.lifedesk.ai.ChatMessage> historyWith = new ArrayList<>(history);
        if (!contextItems.isEmpty()) {
            StringBuilder ctxText = new StringBuilder("Ngữ cảnh người dùng đính kèm:\n");
            for (ContextItem item : contextItems) {
                ctxText.append("\n--- ").append(item.label()).append(" ---\n").append(item.content()).append('\n');
            }
            historyWith.add(com.lifedesk.ai.ChatMessage.system(ctxText.toString()));
        }
        contextItems.clear();
        refreshContextBar();

        renderThrottle.setOnFinished(e -> {
            if (gen == chatGen) {
                liveLabel.setVisible(false);
                replyPane.render(streaming + " ▍", ctx.themeManager().previewPalette(ctx.config().theme));
            }
        });

        ctx.ai().chatStreamAsync(ctx.executors().ai(), List.copyOf(historyWith), question,
                delta -> {
                    streaming.append(delta);
                    // throttle renders from the background thread onto the FX thread
                    Fx.runLater(renderThrottle::playFromStart);
                },
                reply -> Fx.runLater(() -> {
                    if (gen != chatGen) {
                        return;
                    }
                    liveLabel.setVisible(false);
                    replyPane.render(reply, ctx.themeManager().previewPalette(ctx.config().theme));
                    history.add(com.lifedesk.ai.ChatMessage.user(question));
                    history.add(com.lifedesk.ai.ChatMessage.assistant(reply));
                    trimHistory();
                    setBusy(false);
                }),
                err -> Fx.runLater(() -> {
                    if (gen != chatGen) {
                        return;
                    }
                    replyCard.getChildren().remove(liveLabel);
                    Label errLabel = new Label("Lỗi provider: " + err.getMessage()
                            + "\n(Thử lại, đổi provider trong Cài đặt → AI, hoặc dùng engine quy tắc offline.)");
                    errLabel.getStyleClass().add("dim-label");
                    errLabel.setWrapText(true);
                    replyCard.getChildren().add(errLabel);
                    setBusy(false);
                }));
    }

    /** "Bỏ bubble": user turn is a compact labeled row; the AI turn is the rendered card above. */
    private void addUserRow(String text) {
        chatBox.getChildren().remove(emptyState);
        Label who = new Label("Bạn");
        who.getStyleClass().add("overline");
        Label msg = new Label(text);
        msg.setWrapText(true);
        msg.setMaxWidth(Double.MAX_VALUE);
        msg.getStyleClass().add("chat-bubble-user");
        VBox row = new VBox(2, who, msg);
        row.setMaxWidth(620);
        HBox wrap = new HBox(row);
        wrap.setAlignment(Pos.CENTER_RIGHT);
        wrap.setMaxWidth(Double.MAX_VALUE);
        chatBox.getChildren().add(wrap);
    }

    /** Abandon the in-flight request: its result is ignored and the UI is usable again. */
    private void stopChat() {
        if (!busy) {
            return;
        }
        chatGen++;
        setBusy(false);
        main.status("Đã dừng yêu cầu AI");
    }

    private void startNewChat() {
        if (busy) {
            stopChat();
        }
        chatGen++;
        history.clear();
        contextItems.clear();
        refreshContextBar();
        chatBox.getChildren().clear();
        emptyState = new EmptyState("bot",
                "Chưa có hội thoại. @ để gắn ghi chú/dữ liệu, / để dùng mẫu lệnh.");
        chatBox.getChildren().add(emptyState);
        setBusy(false);
        main.status("Đã bắt đầu cuộc trò chuyện mới");
    }

    private static String capitalizeFirst(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void trimHistory() {
        int max = Math.max(2, ctx.config().ai.maxHistoryMessages);
        while (history.size() > max) {
            history.remove(0);
        }
    }

    @Override
    public void onShow() {
        refreshStatus();
    }
}
