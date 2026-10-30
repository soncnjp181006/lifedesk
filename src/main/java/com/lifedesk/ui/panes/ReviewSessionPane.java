package com.lifedesk.ui.panes;

import com.lifedesk.app.AppContext;
import com.lifedesk.learning.Flashcard;
import com.lifedesk.markdown.MarkdownPreviewPane;
import com.lifedesk.theme.ThemeManager;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.util.List;

/**
 * Flashcard review session (the missing flow over the Leitner engine):
 * deck picker → card front → flip → rate (remembered / forgotten) → next,
 * with a session timer and end-of-session stats. Card content renders as
 * markdown + LaTeX so math decks display properly.
 */
public class ReviewSessionPane extends VBox {
    private static final String MODE_ALL = "Tất cả thẻ — học ngay";
    private static final String MODE_DUE = "Chỉ thẻ đến hạn";
    private static final String ALL_DECKS = "(tất cả các bộ)";

    private final AppContext ctx;
    private final ComboBox<String> deckPick = new ComboBox<>();
    private final ComboBox<String> modePick = new ComboBox<>();
    private final javafx.scene.control.CheckBox shuffleBox = new javafx.scene.control.CheckBox("Trộn thẻ");
    private final MarkdownPreviewPane cardPane = new MarkdownPreviewPane();
    private final Label progressLabel = new Label();
    private final Label statsLabel = new Label();
    private final Button flipBtn = new Button("Lật thẻ (Space)");
    private final Button correctBtn = new Button("Nhớ ✓");
    private final Button wrongBtn = new Button("Quên ✗");
    private final PauseTransition timer = new PauseTransition(Duration.seconds(1));

    private List<Flashcard> queue = List.of();
    private int index = 0;
    private boolean showingBack;
    private int correct = 0;
    private int reviewed = 0;
    private long elapsedSeconds = 0;
    /** Last markdown handed to the card pane, so a theme switch can re-render it. */
    private String cardMarkdown = "";

    public ReviewSessionPane(AppContext ctx) {
        this.ctx = ctx;
        setSpacing(12);

        deckPick.getItems().add(ALL_DECKS);
        for (var d : ctx.learning().decks()) {
            deckPick.getItems().add(d.name);
        }
        deckPick.setValue(ALL_DECKS);
        deckPick.setTooltip(new javafx.scene.control.Tooltip("Bộ thẻ muốn học"));
        deckPick.setOnAction(e -> startSession());

        // Quizlet behavior: study ANY deck ANY time. Due-only is an opt-in mode —
        // previously a deck with no due cards left the session pane dead, so the
        // user's own cards were unstudiable.
        modePick.getItems().addAll(MODE_ALL, MODE_DUE);
        modePick.setValue(MODE_ALL);
        modePick.setTooltip(new javafx.scene.control.Tooltip("Phạm vi thẻ trong phiên học"));
        modePick.setOnAction(e -> startSession());
        shuffleBox.setTooltip(new javafx.scene.control.Tooltip("Xáo trộn thứ tự thẻ"));
        shuffleBox.setOnAction(e -> startSession());

        Button startBtn = new Button("Bắt đầu phiên ôn");
        startBtn.getStyleClass().add("primary-btn");
        startBtn.setOnAction(e -> startSession());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, new Label("Ôn tập spaced repetition"), spacer,
                modePick, deckPick, shuffleBox, startBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        cardPane.setMinHeight(220);
        cardPane.getStyleClass().add("card");

        flipBtn.getStyleClass().add("primary-btn");
        flipBtn.setOnAction(e -> flip());
        correctBtn.getStyleClass().add("primary-btn");
        correctBtn.setDisable(true);
        correctBtn.setOnAction(e -> answer(true));
        wrongBtn.getStyleClass().add("danger-btn");
        wrongBtn.setDisable(true);
        wrongBtn.setOnAction(e -> answer(false));
        HBox answerRow = new HBox(10, flipBtn, correctBtn, wrongBtn);
        answerRow.setAlignment(Pos.CENTER);
        progressLabel.getStyleClass().add("dim-label");
        statsLabel.getStyleClass().add("dim-label");

        timer.setOnFinished(e -> {
            elapsedSeconds++;
            updateStats();
            timer.playFromStart();
        });

        HBox statsRow = new HBox(10, progressLabel, new Region(), statsLabel);
        HBox.setHgrow(statsRow.getChildren().get(1), Priority.ALWAYS);

        getChildren().addAll(header, cardPane, answerRow, statsRow);
        startSession();
    }

    /** Space = flip, 1 = forgotten, 2 = remembered (study hotkeys without focus change). */
    public boolean handleKey(javafx.scene.input.KeyEvent e) {
        if (e.getCode() == javafx.scene.input.KeyCode.SPACE) {
            flip();
            return true;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.DIGIT1 && showingBack) {
            answer(false);
            return true;
        }
        if (e.getCode() == javafx.scene.input.KeyCode.DIGIT2 && showingBack) {
            answer(true);
            return true;
        }
        return false;
    }

    private void startSession() {
        String deckName = deckPick.getValue();
        String deckId = null;
        if (deckName != null && !deckName.startsWith("(")) {
            for (var d : ctx.learning().decks()) {
                if (d.name.equals(deckName)) {
                    deckId = d.id;
                    break;
                }
            }
        }
        // Quizlet-style pool: "Tất cả thẻ" studies the whole deck selection any
        // time (answers still feed the Leitner boxes); "Chỉ thẻ đến hạn" is the
        // classic spaced-repetition queue.
        boolean dueOnly = MODE_DUE.equals(modePick.getValue());
        if (dueOnly) {
            queue = ctx.learning().dueCards(deckId);
        } else {
            List<Flashcard> pool = new java.util.ArrayList<>();
            if (deckId != null) {
                pool.addAll(ctx.learning().cardsInDeck(deckId));
            } else {
                for (var d : ctx.learning().decks()) {
                    pool.addAll(ctx.learning().cardsInDeck(d.id));
                }
            }
            if (shuffleBox.isSelected()) {
                java.util.Collections.shuffle(pool);
            }
            queue = pool;
        }
        index = 0;
        correct = 0;
        reviewed = 0;
        elapsedSeconds = 0;
        timer.playFromStart();
        showCurrent();
    }

    private void showCurrent() {
        if (queue.isEmpty()) {
            boolean dueOnly = MODE_DUE.equals(modePick.getValue());
            renderCard(dueOnly
                    ? "**Không có thẻ nào đến hạn ôn.**\n\nChọn chế độ **Tất cả thẻ — học ngay** "
                            + "để học mọi thẻ, bất kể đến hạn."
                    : "**Bộ này chưa có thẻ nào.**\n\nThêm thẻ mới ở tab Flashcards.");
            progressLabel.setText("0 thẻ");
            statsLabel.setText("");
            flipBtn.setDisable(true);
            correctBtn.setDisable(true);
            wrongBtn.setDisable(true);
            return;
        }
        flipBtn.setDisable(index >= queue.size());
        Flashcard card = index < queue.size() ? queue.get(index) : null;
        if (card == null) {
            finishSession();
            return;
        }
        showingBack = false;
        correctBtn.setDisable(true);
        wrongBtn.setDisable(true);
        renderCard(card.front);
        progressLabel.setText("Thẻ " + (index + 1) + "/" + queue.size()
                + "  —  box " + card.box);
        updateStats();
    }

    /** Re-paint the current card with the active theme (called on theme switch). */
    public void retheme() {
        renderCard(cardMarkdown);
    }

    private void renderCard(String markdown) {
        cardMarkdown = markdown;
        cardPane.render(markdown, palette());
    }

    private void flip() {
        if (index >= queue.size()) {
            return;
        }
        showingBack = true;
        Flashcard card = queue.get(index);
        renderCard("---\n\n" + card.back);
        correctBtn.setDisable(false);
        wrongBtn.setDisable(false);
        flipBtn.setDisable(true);
    }

    private void answer(boolean remembered) {
        if (index >= queue.size()) {
            return;
        }
        Flashcard card = queue.get(index);
        ctx.learning().review(card.id, remembered);
        reviewed++;
        if (remembered) {
            correct++;
        }
        index++;
        showCurrent();
    }

    private void finishSession() {
        timer.stop();
        int minutes = (int) Math.max(1, elapsedSeconds / 60);
        renderCard("**Hoàn tất phiên ôn!**\n\n"
                + "• Đã ôn " + reviewed + " thẻ\n"
                + "• Nhớ: " + correct + " — Quên: " + (reviewed - correct) + "\n"
                + "• Thời gian: " + minutes + " phút");
        progressLabel.setText("Phiên kết thúc");
        String topic = "Ôn tập " + LocalDate.now() + " (" + reviewed + " thẻ)";
        ctx.learning().addSession(null, LocalDate.now(), minutes, topic);
        flipBtn.setDisable(true);
        correctBtn.setDisable(true);
        wrongBtn.setDisable(true);
    }

    private void updateStats() {
        statsLabel.setText("Nhớ " + correct + "/" + reviewed
                + (elapsedSeconds > 0 ? "  —  " + (elapsedSeconds / 60) + " phút" : ""));
    }

    private ThemeManager.PreviewPalette palette() {
        return ctx.themeManager().previewPalette(ctx.config().theme);
    }
}
