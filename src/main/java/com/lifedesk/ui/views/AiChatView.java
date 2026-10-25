package com.lifedesk.ui.views;

import com.lifedesk.ai.AiProvider;
import com.lifedesk.app.AppContext;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
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
        Button settingsBtn = new Button("⚙ Cài đặt AI");
        settingsBtn.setOnAction(e -> main.showView(MainWindow.ViewId.SETTINGS));
        HBox header = new HBox(10, title, spacer, providerStatus, settingsBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox quickRow = new HBox(8);
        for (String quick : List.of("việc hôm nay", "quá hạn", "gợi ý", "chi tiêu tháng này",
                "thói quen", "ôn tập", "sức khỏe")) {
            Button b = new Button(quick);
            b.getStyleClass().add("ghost-btn");
            b.setOnAction(e -> {
                input.setText(quick);
                send();
            });
            quickRow.getChildren().add(b);
        }

        input.setPromptText("Nhập câu hỏi hoặc lệnh… (Enter để gửi)");
        input.setOnAction(e -> send());
        Button sendBtn = new Button("Gửi ➤");
        sendBtn.getStyleClass().add("primary-btn");
        sendBtn.setOnAction(e -> send());

        HBox inputRow = new HBox(8, input, sendBtn);
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
        refreshStatus();
        return root;
    }

    private void refreshStatus() {
        if (!ctx.ai().enabled()) {
            providerStatus.setText("AI: ĐANG TẮT (bật trong Cài đặt)");
            chatBox.getChildren().setAll(new EmptyState("🤖",
                    "Tính năng AI đang tắt trong Cài đặt → AI. Các module khác vẫn hoạt động bình thường.",
                    settingsButton()));
            return;
        }
        var provider = ctx.ai().currentProvider();
        provider.ifPresentOrElse(p -> {
            boolean online = p.isAvailable();
            providerStatus.setText("Provider: " + p.displayName() + (online ? "  ● sẵn sàng" : "  ● không kết nối được"));
            providerStatus.getStyleClass().add("dim-label");
        }, () -> providerStatus.setText("AI: tắt"));
    }

    private Button settingsButton() {
        Button b = new Button("Mở Cài đặt AI");
        b.getStyleClass().add("primary-btn");
        b.setOnAction(e -> main.showView(MainWindow.ViewId.SETTINGS));
        return b;
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
        busy = true;
        input.clear();
        addBubble("user", text);
        VBox thinking = addBubble("ai", "…đang xử lý");
        var provider = ctx.ai().currentProvider();
        if (provider.isEmpty()) {
            thinking.getChildren().setAll(new Label("AI tắt."));
            busy = false;
            return;
        }
        ctx.ai().chatAsync(ctx.executors().ai(), history, text,
                reply -> {
                    thinking.getChildren().setAll(wrapLabel(reply, "chat-bubble-ai"));
                    history.add(com.lifedesk.ai.ChatMessage.user(text));
                    history.add(com.lifedesk.ai.ChatMessage.assistant(reply));
                    trimHistory();
                    busy = false;
                },
                err -> {
                    thinking.getChildren().setAll(wrapLabel(
                            "⚠ Lỗi provider: " + err.getMessage()
                                    + "\n(Thử lại, đổi provider trong Cài đặt → AI, hoặc dùng engine quy tắc offline.)",
                            "chat-bubble-ai"));
                    busy = false;
                });
    }

    private void trimHistory() {
        int max = Math.max(2, ctx.config().ai.maxHistoryMessages);
        while (history.size() > max) {
            history.remove(0);
        }
    }

    private VBox addBubble(String who, String text) {
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
