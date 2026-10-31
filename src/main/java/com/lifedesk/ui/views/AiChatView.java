package com.lifedesk.ui.views;

import com.lifedesk.ai.AiProvider;
import com.lifedesk.app.AppContext;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.IconView;
import com.lifedesk.ui.components.MarkdownFlow;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI chat.
 *
 * <p>Structure: a bordered conversation surface ("khung chat") with its own
 * header, a scrolling message column and a composer pinned at the bottom. The
 * assistant turn is rendered as markdown with {@link MarkdownFlow} and re-rendered
 * on a short throttle while the provider streams, so text appears as it is
 * generated. No WebView is involved: the previous implementation created one per
 * reply and reloaded its HTML on every tick, which is what exhausted native
 * memory and killed the process.
 *
 * <p>Smart composer: "@" attaches app data or a note as context and "/" inserts a
 * prompt template.
 */
public class AiChatView extends View {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final List<com.lifedesk.ai.ChatMessage> history = new ArrayList<>();
    /** Persisted conversation currently open (null = fresh, unsaved chat). */
    private String currentConvId;
    private boolean updatingSelection;
    private final javafx.scene.control.ListView<com.lifedesk.ai.AiConversationService.Conversation> convList =
            new javafx.scene.control.ListView<>();
    private final Label chatTitleLabel = new Label("Hội thoại");
    /** Context items attached via "@". */
    private final List<ContextItem> contextItems = new ArrayList<>();

    private final VBox messages = new VBox(16);
    private final ScrollPane chatScroll = new ScrollPane(messages);
    private final HBox contextBar = new HBox(6);
    private final TextArea input = new TextArea();
    private final Label providerStatus = new Label();
    private final Label modelChip = new Label();
    private final Label countChip = new Label();
    private final Button sendBtn = new Button();
    private final VBox composer = new VBox(8);

    /** Throttled re-render of the in-flight reply. */
    private final PauseTransition streamTick = new PauseTransition(Duration.millis(70));
    private final StringBuilder streamBuffer = new StringBuilder();

    private Node emptyState;
    private Exchange active;
    /** Bumps on stop/new-chat so a late provider reply cannot touch the UI. */
    private int chatGen = 0;
    private volatile boolean busy = false;
    /** Guards against a slow probe from an earlier refresh overwriting a newer one. */
    private int statusTicket = 0;
    private boolean stickToBottom = true;
    private Popup picker;
    /** Start index of the @-token being typed (for replacing it on selection). */
    private int atTokenStart = -1;
    private String lastQuestion = "";

    /** One attached context: an app-data summary or a full note. */
    private record ContextItem(String label, String content) {
    }

    /** The UI pieces of one assistant turn. */
    private static final class Exchange {
        final VBox bubble;
        final MarkdownFlow flow;
        final HBox assistantRow;
        final HBox userRow;
        final String question;

        Exchange(VBox bubble, MarkdownFlow flow, HBox assistantRow, HBox userRow, String question) {
            this.bubble = bubble;
            this.flow = flow;
            this.assistantRow = assistantRow;
            this.userRow = userRow;
            this.question = question;
        }
    }

    public AiChatView(AppContext ctx, MainWindow main) {
        super(ctx, main);
        setSpacing(12);
    }

    @Override
    public String id() {
        return "AI";
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected Node build() {
        // ---- history sidebar (ChatGPT-style conversation list)
        Label sidebarBrand = new Label("Trợ lý AI");
        sidebarBrand.getStyleClass().add("section-label");
        Button newChatBtn = new Button("Hội thoại mới", new IconView("plus", 15));
        newChatBtn.getStyleClass().add("primary-btn");
        newChatBtn.setMaxWidth(Double.MAX_VALUE);
        newChatBtn.setOnAction(e -> startNewChat());
        Label recentLabel = new Label("Gần đây");
        recentLabel.getStyleClass().add("dim-label");
        convList.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            private final Button removeBtn = new Button("", new IconView("trash", 13));

            {
                removeBtn.getStyleClass().add("icon-btn");
                removeBtn.setTooltip(new Tooltip("Xóa hội thoại này"));
                removeBtn.setOnAction(e -> {
                    com.lifedesk.ai.AiConversationService.Conversation conv = getItem();
                    if (conv != null && Dialogs.confirm("Xóa hội thoại",
                            "Xóa \"" + conv.title + "\"?")) {
                        ctx.aiConversations().delete(conv.id);
                        if (conv.id.equals(currentConvId)) {
                            startNewChat();
                        }
                    }
                });
            }

            @Override
            protected void updateItem(com.lifedesk.ai.AiConversationService.Conversation conv, boolean empty) {
                super.updateItem(conv, empty);
                if (empty || conv == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label titleLabel = new Label(conv.title);
                titleLabel.getStyleClass().add("form-label");
                titleLabel.setMaxWidth(150);
                Label timeLabel = new Label(relateTime(conv.updatedAt));
                timeLabel.getStyleClass().add("chat-meta");
                Region spring = new Region();
                HBox.setHgrow(spring, Priority.ALWAYS);
                HBox box = new HBox(6, new VBox(1, titleLabel, timeLabel), spring, removeBtn);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
                setText(null);
            }
        });
        convList.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> {
            // updatingSelection: programmatic re-select during refresh must not
            // reload the transcript (and sel.id can be null for legacy rows).
            if (sel != null && !updatingSelection && sel.id != null && !sel.id.equals(currentConvId)) {
                loadConversation(sel.id);
            }
        });
        ctx.aiConversations().changes().addListener(this::refreshConvList);
        VBox sidebar = new VBox(10, sidebarBrand, newChatBtn, recentLabel, convList);
        sidebar.getStyleClass().add("chat-sidebar");
        sidebar.setPrefWidth(250);
        sidebar.setMinWidth(210);
        VBox.setVgrow(convList, Priority.ALWAYS);

        // ---- chat column: top bar, messages (the ONLY page scroller), composer
        Label chatTitle = chatTitleLabel;
        chatTitle.getStyleClass().add("section-label");
        modelChip.getStyleClass().addAll("chip", "chip-info");
        modelChip.setVisible(false);
        modelChip.setManaged(false);
        countChip.getStyleClass().add("chip");
        countChip.setVisible(false);
        countChip.setManaged(false);
        Region headSpacer = new Region();
        HBox.setHgrow(headSpacer, Priority.ALWAYS);
        Button settingsBtn = new Button("Cài đặt AI", new IconView("settings", 15));
        settingsBtn.setOnAction(e -> main.showView(MainWindow.ViewId.SETTINGS));
        providerStatus.getStyleClass().add("dim-label");
        // No trash button here: clearing a chat = "Hội thoại mới" in the sidebar —
        // a second path to the same action read as a delete button.
        HBox frameHead = new HBox(8, new IconView("bot", 15, "icon-accent"), chatTitle,
                modelChip, countChip, headSpacer, providerStatus, settingsBtn);
        frameHead.setAlignment(Pos.CENTER_LEFT);
        frameHead.getStyleClass().add("chat-head");

        // ---- message column
        messages.setFillWidth(true);
        messages.setAlignment(Pos.TOP_CENTER); // exchanges center as a column
        messages.getStyleClass().add("chat-messages");
        chatScroll.setFitToWidth(true);
        chatScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        chatScroll.setMinHeight(0);
        chatScroll.getStyleClass().add("chat-scroll");
        VBox.setVgrow(chatScroll, Priority.ALWAYS);
        chatScroll.vvalueProperty().addListener((o, a, b) ->
                stickToBottom = b.doubleValue() > 0.92);

        // ---- composer
        contextBar.setVisible(false);
        contextBar.setManaged(false);
        input.setPromptText("Hỏi AI…  @ gắn ngữ cảnh · / mẫu lệnh · Enter để gửi");
        input.getStyleClass().add("chat-input");
        input.setWrapText(true);
        input.setPrefRowCount(2);
        input.setMinHeight(58);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                send();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                if (busy) {
                    stopChat(); // Esc = the red button, keyboard parity
                } else {
                    hidePicker();
                }
            }
        });
        input.textProperty().addListener((o, old, now) -> {
            onInputChanged(now);
            growInput();
        });
        input.focusedProperty().addListener((o, was, is) -> composer.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("focused"), is));

        sendBtn.setGraphic(new IconView("send", 15));
        sendBtn.setText("");
        sendBtn.getStyleClass().add("primary-btn");
        sendBtn.setTooltip(new Tooltip("Gửi (Enter)"));
        // One dual-mode button: blue = send, red = stop (tap again to stop).
        sendBtn.setOnAction(e -> {
            if (busy) {
                stopChat();
            } else {
                send();
            }
        });
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox inputRow = new HBox(8, input);

        // Every mainstream AI app: a centered, compact composer card — not a
        // full-window strip with buttons stacked in a side column.
        Label hint = new Label("Enter gửi · Shift+Enter xuống dòng · @ dữ liệu · / mẫu lệnh");
        hint.getStyleClass().add("chat-hint");
        Region hintSpacer = new Region();
        HBox.setHgrow(hintSpacer, Priority.ALWAYS);
        Label privacy = new Label("Chỉ gửi dữ liệu khi bạn bật \"đọc dữ liệu\" trong Cài đặt → AI");
        privacy.getStyleClass().add("chat-hint");
        privacy.setWrapText(false);
        HBox composerBottom = new HBox(10, hint, hintSpacer, sendBtn);
        composerBottom.setAlignment(Pos.CENTER_LEFT);

        composer.getStyleClass().add("chat-composer");
        composer.setMaxWidth(880);
        composer.getChildren().addAll(contextBar, inputRow, composerBottom);
        HBox composerWrap = new HBox(composer);
        composerWrap.setAlignment(Pos.CENTER);
        composerWrap.setPadding(new javafx.geometry.Insets(8, 14, 12, 14));

        streamTick.setOnFinished(e -> {
            if (active != null && busy) {
                active.flow.render(streamBuffer.toString(), true);
                scrollToEnd();
            }
        });

        VBox frame = new VBox(0, frameHead, chatScroll, composerWrap);
        frame.getStyleClass().add("chat-surface");
        HBox.setHgrow(frame, Priority.ALWAYS);

        showEmptyState();
        refreshStatus();
        setBusy(false);
        refreshConvList();

        // Sidebar + chat column fill the viewport: the page itself never scrolls,
        // only the message area does (ChatGPT layout).
        HBox root = new HBox(12, sidebar, frame);
        root.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(root, Priority.ALWAYS);
        return root;
    }

    // ------------------------------------------------------------------ history sidebar

    /** Refresh the sidebar list, keeping the current conversation selected. */
    private void refreshConvList() {
        convList.getItems().setAll(ctx.aiConversations().list());
        if (currentConvId != null) {
            convList.getItems().stream()
                    .filter(c -> c.id.equals(currentConvId))
                    .findFirst()
                    .ifPresent(c -> {
                        updatingSelection = true;
                        convList.getSelectionModel().select(c);
                        updatingSelection = false;
                    });
        }
    }

    private void persistConversation() {
        if (history.isEmpty()) {
            return;
        }
        com.lifedesk.ai.AiConversationService.Conversation conv =
                currentConvId == null
                        ? new com.lifedesk.ai.AiConversationService.Conversation()
                        : ctx.aiConversations().get(currentConvId)
                                .orElseGet(com.lifedesk.ai.AiConversationService.Conversation::new);
        currentConvId = conv.id;
        conv.title = history.stream()
                .filter(m -> m.role().equals("user"))
                .findFirst()
                .map(m -> m.content().length() > 48 ? m.content().substring(0, 48) + "…" : m.content())
                .orElse("Hội thoại mới");
        conv.messages = history.stream()
                .map(m -> new com.lifedesk.ai.AiConversationService.Message(m.role(), m.content()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        ctx.aiConversations().upsert(conv);
        chatTitleLabel.setText(conv.title);
        refreshConvList();
    }

    /** Open a stored conversation and rebuild the transcript on screen. */
    private void loadConversation(String id) {
        if (busy) {
            stopChat();
        }
        chatGen++;
        var stored = ctx.aiConversations().get(id).orElse(null);
        if (stored == null) {
            return;
        }
        currentConvId = stored.id;
        history.clear();
        contextItems.clear();
        active = null;
        setBusy(false);
        messages.getChildren().clear();
        HBox lastUserRow = null;
        for (var m : stored.messages) {
            if (m.role.equals("user")) {
                lastUserRow = addUserMessage(m.content);
                history.add(com.lifedesk.ai.ChatMessage.user(m.content));
            } else {
                Exchange ex = addAssistantMessage(lastUserRow, "");
                ex.flow.render(m.content, false);
                ex.bubble.getChildren().remove(ex.bubble.getChildren().size() - 1);
                ex.bubble.getChildren().add(assistantFooter(m.content));
                history.add(com.lifedesk.ai.ChatMessage.assistant(m.content));
            }
        }
        // history already holds assistant messages; user messages replayed above
        refreshContextBar();
        updateCount();
        chatTitleLabel.setText(stored.title);
        refreshConvList();
        scrollToEnd();
        if (messages.getChildren().isEmpty()) {
            showEmptyState();
        }
    }

    /** "5 phút trước"-style label for the sidebar. */
    private String relateTime(java.time.Instant t) {
        long minutes = java.time.Duration.between(t, java.time.Instant.now()).toMinutes();
        if (minutes < 1) {
            return "vừa xong";
        }
        if (minutes < 60) {
            return minutes + " phút trước";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " giờ trước";
        }
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(t);
    }

    private void growInput() {
        int lines = Math.min(6, Math.max(2, 1 + (int) input.getText().chars().filter(c -> c == '\n').count()));
        input.setPrefRowCount(lines);
    }

    private void scrollToEnd() {
        if (!stickToBottom) {
            return;
        }
        Platform.runLater(() -> {
            chatScroll.applyCss();
            chatScroll.layout();
            chatScroll.setVvalue(1.0);
        });
    }

    // ------------------------------------------------------------------ empty state

    private void showEmptyState() {
        VBox card = new VBox(14);
        card.getStyleClass().add("chat-empty");
        card.setAlignment(Pos.CENTER);
        StackPane avatar = new StackPane(new IconView("bot", 26, "icon-accent"));
        avatar.getStyleClass().add("chat-avatar-large");
        Label headline = new Label("Bắt đầu một cuộc trò chuyện");
        headline.getStyleClass().add("card-headline");
        Label sub = new Label("Hỏi về công việc, chi tiêu, thói quen hay ghi chú của bạn. "
                + "Gõ @ để đính kèm dữ liệu, / để dùng mẫu lệnh.");
        sub.getStyleClass().add("page-subtitle");
        sub.setWrapText(true);
        sub.setMaxWidth(560);
        sub.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        FlowPane suggestions = new FlowPane(8, 8);
        suggestions.setAlignment(Pos.CENTER);
        suggestions.setMaxWidth(640);
        for (String prompt : List.of(
                "Hôm nay tôi cần làm gì?",
                "Tháng này tôi chi bao nhiêu?",
                "Tôi nên ưu tiên việc gì trước?",
                "Tóm tắt tình hình thói quen của tôi",
                "Có flashcard nào cần ôn không?")) {
            Button chip = new Button(prompt);
            chip.getStyleClass().add("suggestion-chip");
            chip.setOnAction(e -> {
                input.setText(prompt);
                send();
            });
            suggestions.getChildren().add(chip);
        }
        card.getChildren().addAll(avatar, headline, sub, suggestions);
        emptyState = card;
        messages.getChildren().setAll(card);
        updateCount();
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

    /** Context picker: app data summaries + every note. */
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
        var screen = input.localToScreen(0, 0);
        if (screen != null) {
            picker.show(input, screen.getX(), Math.max(0, screen.getY() - pickerHeight(options)));
        }
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
        if (!any) {
            return;
        }
        Label hint = new Label("Ngữ cảnh:");
        hint.getStyleClass().add("chat-hint");
        contextBar.getChildren().add(hint);
        for (ContextItem item : contextItems) {
            Label chip = new Label(item.label());
            chip.getStyleClass().add("chip");
            chip.setMaxWidth(260);
            contextBar.getChildren().add(chip);
        }
        Button clear = new Button("Gỡ hết", new IconView("circle-x", 12));
        clear.getStyleClass().add("ghost-btn");
        clear.setOnAction(e -> {
            contextItems.clear();
            refreshContextBar();
        });
        contextBar.getChildren().add(clear);
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
            sb.append("- ").append(t.title)
                    .append(t.dueDate != null ? " (hạn " + t.dueDate + ")" : "").append('\n');
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
            sb.append("- ").append(h.name).append(" (chuỗi ")
                    .append(ctx.habits().currentStreak(h)).append(" ngày)\n");
        }
        for (var g : ctx.habits().goals()) {
            sb.append("- [Mục tiêu] ").append(g.title()).append(" — ").append(g.status()).append('\n');
        }
        return sb.isEmpty() ? "(không có)" : sb.toString();
    }

    // ------------------------------------------------------------------ status

    private void refreshStatus() {
        String model = ctx.ai().activeModel();
        modelChip.setText(model == null || model.isBlank() ? "mặc định" : model);
        modelChip.setVisible(!model.isBlank());
        modelChip.setManaged(!model.isBlank());
        if (!ctx.ai().enabled()) {
            providerStatus.setText("AI đang tắt");
            providerStatus.setGraphic(new IconView("triangle-alert", 13, "icon-warn"));
            setComposerEnabled(false, "Bật AI trong Cài đặt → AI để trò chuyện.");
            return;
        }
        var provider = ctx.ai().currentProvider();
        if (provider.isEmpty()) {
            providerStatus.setGraphic(null);
            providerStatus.setText("AI: tắt");
            setComposerEnabled(false, "Chưa có provider nào đang bật.");
            return;
        }
        AiProvider p = provider.get();
        setComposerEnabled(true, "");
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
        providerStatus.setText(p.displayName()
                + (online == null ? "  ·  đang kiểm tra" : online ? "" : "  ·  không kết nối được"));
        providerStatus.setGraphic(online == null
                ? new IconView("hourglass", 13, "icon-dim")
                : new IconView(online ? "circle-check" : "triangle-alert", 13,
                        online ? "icon-ok" : "icon-danger"));
    }

    private void setComposerEnabled(boolean enabled, String note) {
        input.setDisable(!enabled);
        sendBtn.setDisable(!enabled || busy);
        input.setPromptText(enabled ? "Hỏi AI…  @ gắn ngữ cảnh · / mẫu lệnh · Enter để gửi"
                : note);
    }

    private void setBusy(boolean running) {
        busy = running;
        // Dual-mode button: blue send ↔ red stop.
        sendBtn.getStyleClass().removeAll("primary-btn", "danger-btn");
        sendBtn.getStyleClass().add(running ? "danger-btn" : "primary-btn");
        sendBtn.setGraphic(new IconView(running ? "circle-x" : "send", 15));
        sendBtn.setTooltip(new Tooltip(running ? "Dừng tạo" : "Gửi (Enter)"));
        sendBtn.setDisable(!running && !ctx.ai().enabled());
    }

    private void updateCount() {
        int turns = (int) history.stream().filter(m -> m.role().equals("assistant")).count();
        countChip.setVisible(turns > 0);
        countChip.setManaged(turns > 0);
        countChip.setText(turns + " lượt");
    }

    // ------------------------------------------------------------------ chat flow

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
        lastQuestion = question;
        setBusy(true);
        input.clear();
        hidePicker();

        HBox userRow = addUserMessage(question);
        Exchange exchange = addAssistantMessage(userRow, question);
        active = exchange;
        streamBuffer.setLength(0);
        stickToBottom = true;
        scrollToEnd();

        List<com.lifedesk.ai.ChatMessage> historyWith = new ArrayList<>(history);
        if (!contextItems.isEmpty()) {
            StringBuilder ctxText = new StringBuilder("Ngữ cảnh người dùng đính kèm:\n");
            for (ContextItem item : contextItems) {
                ctxText.append("\n--- ").append(item.label()).append(" ---\n")
                        .append(item.content()).append('\n');
            }
            historyWith.add(com.lifedesk.ai.ChatMessage.system(ctxText.toString()));
        }
        contextItems.clear();
        refreshContextBar();

        ctx.ai().chatStreamAsync(ctx.executors().ai(), List.copyOf(historyWith), question,
                delta -> {
                    streamBuffer.append(delta);
                    Fx.runLater(streamTick::playFromStart);
                },
                reply -> Fx.runLater(() -> finish(exchange, reply, gen)),
                err -> Fx.runLater(() -> fail(exchange, err, gen)));
    }

    private void finish(Exchange exchange, String reply, int gen) {
        if (gen != chatGen) {
            return;
        }
        String text = reply == null || reply.isBlank() ? streamBuffer.toString() : reply;
        exchange.flow.render(text, false);
        exchange.bubble.getChildren().remove(exchange.bubble.getChildren().size() - 1);
        exchange.bubble.getChildren().add(assistantFooter(text));
        history.add(com.lifedesk.ai.ChatMessage.user(exchange.question));
        history.add(com.lifedesk.ai.ChatMessage.assistant(text));
        trimHistory();
        persistConversation();
        active = null;
        setBusy(false);
        updateCount();
        scrollToEnd();
        main.status("AI đã trả lời (" + text.length() + " ký tự)");
    }

    private void fail(Exchange exchange, Throwable err, int gen) {
        if (gen != chatGen) {
            return;
        }
        exchange.bubble.getChildren().remove(exchange.bubble.getChildren().size() - 1);
        String message = err.getMessage() == null ? err.toString() : err.getMessage();
        Label error = new Label("Không nhận được câu trả lời: " + message
                + "\nKiểm tra Cài đặt → AI (provider, model, API key) hoặc chuyển sang engine quy tắc offline.");
        error.getStyleClass().add("chat-error");
        error.setWrapText(true);
        Button retry = new Button("Thử lại", new IconView("recycle", 14));
        retry.setOnAction(e -> retry(exchange));
        Button settings = new Button("Mở Cài đặt AI", new IconView("settings", 14));
        settings.setOnAction(e -> main.showView(MainWindow.ViewId.SETTINGS));
        HBox actions = new HBox(8, retry, settings);
        exchange.bubble.getChildren().addAll(error, actions);
        active = null;
        setBusy(false);
        scrollToEnd();
        main.status("AI lỗi: " + message);
    }

    /** Drop the failed turn and ask the same question again. */
    private void retry(Exchange exchange) {
        messages.getChildren().removeAll(exchange.userRow, exchange.assistantRow);
        if (messages.getChildren().isEmpty()) {
            showEmptyState();
        }
        input.setText(exchange.question);
        input.positionCaret(exchange.question.length());
        send();
    }

    private HBox addUserMessage(String text) {
        messages.getChildren().remove(emptyState);
        Label body = new Label(text);
        body.setWrapText(true);
        body.getStyleClass().add("chat-bubble-user");
        body.setMaxWidth(620);
        Label meta = new Label("Bạn · " + LocalTime.now().format(CLOCK));
        meta.getStyleClass().add("chat-meta");
        VBox bubble = new VBox(3, body, meta);
        bubble.setAlignment(Pos.TOP_RIGHT);
        bubble.setMaxWidth(640);
        HBox row = new HBox(bubble);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setMaxWidth(920);
        row.getStyleClass().add("chat-row");
        messages.getChildren().add(row);
        updateCount();
        return row;
    }

    private Exchange addAssistantMessage(HBox userRow, String question) {
        messages.getChildren().remove(emptyState);
        MarkdownFlow flow = new MarkdownFlow();
        flow.setLinkHandler(this::openLink);
        flow.render("", false);
        VBox bubble = new VBox(flow);
        bubble.getStyleClass().add("chat-bubble-ai");
        bubble.setMaxWidth(880);
        Label thinking = new Label("▍ đang trả lời…");
        thinking.getStyleClass().add("chat-thinking");
        bubble.getChildren().add(thinking);

        StackPane avatar = new StackPane(new IconView("bot", 16, "icon-accent"));
        avatar.getStyleClass().add("chat-avatar");
        VBox column = new VBox(4, new Label("LifeDesk AI"), bubble);
        ((Label) column.getChildren().get(0)).getStyleClass().add("chat-author");
        HBox.setHgrow(column, Priority.ALWAYS);
        column.setMaxWidth(900);
        StackPane avatarBox = new StackPane(avatar);
        avatarBox.setAlignment(Pos.TOP_LEFT);
        avatarBox.setMinWidth(34);
        HBox row = new HBox(10, avatarBox, column);
        row.setAlignment(Pos.TOP_LEFT);
        row.setMaxWidth(920);
        row.getStyleClass().add("chat-row");
        messages.getChildren().add(row);
        scrollToEnd();
        return new Exchange(bubble, flow, row, userRow, question);
    }

    private Node assistantFooter(String reply) {
        Button copy = new Button("Sao chép", new IconView("clipboard-list", 13));
        copy.getStyleClass().add("ghost-btn");
        copy.setOnAction(e -> {
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(reply);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
            main.status("Đã sao chép câu trả lời dạng markdown");
        });
        Label stamp = new Label(LocalTime.now().format(CLOCK));
        stamp.getStyleClass().add("chat-meta");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, copy, spacer, stamp);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("chat-footer");
        return footer;
    }

    private void openLink(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            main.status("Không mở được liên kết: " + url);
        }
    }

    /** Abandon the in-flight request but keep whatever text already arrived. */
    private void stopChat() {
        if (!busy) {
            return;
        }
        chatGen++;
        streamTick.stop();
        String partial = streamBuffer.toString();
        if (active != null) {
            if (partial.isBlank()) {
                messages.getChildren().removeAll(active.assistantRow, active.userRow);
            } else {
                active.flow.render(partial + "\n\n*— đã dừng —*", false);
                active.bubble.getChildren().remove(active.bubble.getChildren().size() - 1);
                history.add(com.lifedesk.ai.ChatMessage.user(active.question));
                history.add(com.lifedesk.ai.ChatMessage.assistant(partial));
                trimHistory();
                persistConversation();
                updateCount();
            }
        }
        if (messages.getChildren().isEmpty()) {
            showEmptyState();
        }
        active = null;
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
        active = null;
        currentConvId = null;
        chatTitleLabel.setText("Hội thoại");
        updatingSelection = true;
        convList.getSelectionModel().clearSelection();
        updatingSelection = false;
        refreshContextBar();
        showEmptyState();
        setBusy(false);
        main.status("Đã bắt đầu cuộc trò chuyện mới");
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
        scrollToEnd();
        pinToViewport();
        refreshConvList();
    }

    private boolean viewportPinned;

    /**
     * Pin this view's height to the outer content viewport. The chat ScrollPane's
     * pref height grows with its content, which used to push the whole view past
     * the viewport and let the PAGE scroll. With the cap, the page never scrolls —
     * only the message area does; the deficit is absorbed by the chat ScrollPane
     * (min height 0) and the conversation list.
     */
    private void pinToViewport() {
        if (viewportPinned || getScene() == null) {
            return;
        }
        for (javafx.scene.Node n = this; n != null; n = n.getParent()) {
            if (n instanceof javafx.scene.control.ScrollPane sp) {
                javafx.beans.InvalidationListener pin = obs ->
                        setMaxHeight(sp.getViewportBounds().getHeight());
                sp.viewportBoundsProperty().addListener(pin);
                pin.invalidated(sp.viewportBoundsProperty());
                convList.setMinHeight(0);
                viewportPinned = true;
                return;
            }
        }
    }

    @Override
    public void onHide() {
        streamTick.stop();
    }
}
