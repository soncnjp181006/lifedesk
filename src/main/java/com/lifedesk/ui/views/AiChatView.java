package com.lifedesk.ui.views;

import com.lifedesk.ai.AiProvider;
import com.lifedesk.app.AppContext;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.components.IconView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/**
 * AI chat. Fully functional with the rule-based engine (offline); switches
 * to Ollama/Kilo when configured in Settings. When AI is disabled in
 * settings this view shows a clear off state and nothing else breaks.
 */
public class AiChatView extends View {
    private final List<com.lifedesk.ai.ChatMessage> history = new ArrayList<>();
    private final VBox chatBox = new VBox(8);
    private final TextField input = new TextField();
    private final Label providerStatus = new Label();
    private volatile boolean busy = false;
    private Node emptyState;
    private Button sendBtn;
    private Button stopBtn;
    /** Bumps on stop/new-chat so a late provider reply cannot touch the UI. */
    private int chatGen = 0;
    private VBox thinking;
    /** Guards against a slow probe from an earlier refresh overwriting a newer one. */
    private int statusTicket = 0;

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
        Button newChatBtn = new Button("Cuộc trò chuyện mới", new IconView("plus", 15));
        newChatBtn.setOnAction(e -> startNewChat());
        Button settingsBtn = new Button("Cài đặt AI", new IconView("settings", 15));
        settingsBtn.setOnAction(e -> main.showView(MainWindow.ViewId.SETTINGS));
        HBox header = new HBox(10, title, spacer, providerStatus, newChatBtn, settingsBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox quickRow = new HBox(8);
        for (String quick : List.of("việc hôm nay", "quá hạn", "gợi ý", "chi tiêu tháng này",
                "thói quen", "ôn tập", "sức khỏe")) {
            // Label is capitalised, but the prompt sent to the engine keeps its
            // original wording (the rule engine normalises it).
            Button b = new Button(capitalizeFirst(quick));
            b.getStyleClass().add("ghost-btn");
            b.setOnAction(e -> {
                input.setText(quick);
                send();
            });
            quickRow.getChildren().add(b);
        }

        input.setPromptText("Nhập câu hỏi hoặc lệnh… (Enter để gửi)");
        input.setOnAction(e -> send());
        sendBtn = new Button("Gửi", new IconView("send", 15));
        sendBtn.getStyleClass().add("primary-btn");
        sendBtn.setOnAction(e -> send());
        stopBtn = new Button("Dừng", new IconView("circle-x", 15));
        stopBtn.getStyleClass().add("danger-btn");
        stopBtn.setOnAction(e -> stopChat());

        HBox inputRow = new HBox(8, input, sendBtn, stopBtn);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);

        ScrollPane chatScroll = new ScrollPane(chatBox);
        chatScroll.setFitToWidth(true);
        chatScroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(chatScroll, Priority.ALWAYS);

        Label disclaimer = new Label("AI chạy hoàn toàn cục bộ theo provider bạn cấu hình; "
                + "dữ liệu chỉ rời máy khi bạn bật provider cloud (Kilo).");
        disclaimer.getStyleClass().add("dim-label");
        disclaimer.setWrapText(true);

        VBox root = new VBox(10, header, quickRow, chatScroll, inputRow, disclaimer);
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        if (history.isEmpty()) {
            emptyState = new EmptyState("bot",
                    "Chưa có hội thoại. Bấm một gợi ý phía trên hoặc gõ câu hỏi bên dưới.");
            chatBox.getChildren().add(emptyState);
        }
        refreshStatus();
        setBusy(false);
        return root;
    }

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
        // Availability is a real HTTP call (3-8s timeout); probing it on the
        // JavaFX thread would freeze the whole window on every show.
        final int ticket = ++statusTicket;
        Thread probe = new Thread(() -> {
            boolean probed;
            try {
                probed = p.isAvailable();
            } catch (RuntimeException e) {
                probed = false;
            }
            final boolean online = probed;
            javafx.application.Platform.runLater(() -> {
                if (ticket == statusTicket) {
                    renderStatus(p, online);
                }
            });
        }, "lifedesk-ai-status");
        probe.setDaemon(true);
        probe.start();
    }

    /** {@code online == null} means the probe has not answered yet. */
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
        final String question = text;
        setBusy(true);
        input.clear();
        addBubble("user", question);
        final VBox bubble = addBubble("ai", "…đang xử lý");
        thinking = bubble;
        // chatAsync invokes its callbacks on the AI pool thread, so every UI change
        // must be marshalled back; doing it inline left `busy` stuck forever.
        ctx.ai().chatAsync(ctx.executors().ai(), List.copyOf(history), question,
                reply -> Fx.runLater(() -> {
                    if (gen != chatGen) {
                        return;
                    }
                    bubble.getChildren().setAll(wrapLabel(reply, "chat-bubble-ai"));
                    history.add(com.lifedesk.ai.ChatMessage.user(question));
                    history.add(com.lifedesk.ai.ChatMessage.assistant(reply));
                    trimHistory();
                    thinking = null;
                    setBusy(false);
                }),
                err -> Fx.runLater(() -> {
                    if (gen != chatGen) {
                        return;
                    }
                    bubble.getChildren().setAll(wrapLabel(
                            "Lỗi provider: " + err.getMessage()
                                    + "\n(Thử lại, đổi provider trong Cài đặt → AI, hoặc dùng engine quy tắc offline.)",
                            "chat-bubble-ai"));
                    thinking = null;
                    setBusy(false);
                }));
    }

    /** Abandon the in-flight request: its result is ignored and the UI is usable again. */
    private void stopChat() {
        if (!busy) {
            return;
        }
        chatGen++;
        setBusy(false);
        if (thinking != null) {
            thinking.getChildren().setAll(wrapLabel("Đã dừng yêu cầu.", "chat-bubble-ai"));
            thinking = null;
        }
        main.status("Đã dừng yêu cầu AI");
    }

    private void startNewChat() {
        if (busy) {
            stopChat();
        }
        chatGen++;
        history.clear();
        thinking = null;
        chatBox.getChildren().clear();
        emptyState = new EmptyState("bot",
                "Chưa có hội thoại. Bấm một gợi ý phía trên hoặc gõ câu hỏi bên dưới.");
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

    private VBox addBubble(String who, String text) {
        chatBox.getChildren().remove(emptyState);
        VBox bubble = wrapLabel(text, "user".equals(who) ? "chat-bubble-user" : "chat-bubble-ai");
        HBox row = new HBox(bubble);
        if ("user".equals(who)) {
            row.setAlignment(Pos.CENTER_RIGHT);
        } else {
            row.setAlignment(Pos.CENTER_LEFT);
        }
        bubble.setMaxWidth(620);
        chatBox.getChildren().add(row);
        return bubble;
    }

    private VBox wrapLabel(String text, String styleClass) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add(styleClass);
        VBox bubble = new VBox(l);
        return bubble;
    }

    @Override
    public void onShow() {
        refreshStatus();
    }
}
