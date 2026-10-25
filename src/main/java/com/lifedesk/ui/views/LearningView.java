package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.learning.Course;
import com.lifedesk.learning.CourseStatus;
import com.lifedesk.learning.Deck;
import com.lifedesk.learning.Flashcard;
import com.lifedesk.learning.LearningService;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
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

import java.time.LocalDate;
import java.util.List;

/** Learning: courses with study sessions, decks of flashcards with Leitner review. */
public class LearningView extends View {
    private final TableView<Course> courseTable = new TableView<>();
    private final TableView<Flashcard> cardTable = new TableView<>();
    private final ListView<Deck> deckList = new ListView<>();
    private final Label dueLabel = new Label();

    public LearningView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "LEARNING";
    }

    @Override
    protected Node build() {
        Label title = new Label("Học tập");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button reviewBtn = new Button("🎯 Ôn tập (" + ctx.learning().dueCount() + " thẻ đến hạn)");
        reviewBtn.getStyleClass().add("primary-btn");
        reviewBtn.setOnAction(e -> startReview());
        HBox header = new HBox(10, title, spacer, dueLabel, reviewBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- courses
        buildCourseTable();
        courseTable.setPlaceholder(new EmptyState("📚", "Chưa có khóa học nào."));
        Button addCourse = new Button("+ Khóa học");
        addCourse.setOnAction(e -> openCourseEditor(null));
        Button editCourse = new Button("✎ Sửa");
        editCourse.setOnAction(e -> {
            Course c = courseTable.getSelectionModel().getSelectedItem();
            if (c != null) {
                openCourseEditor(c);
            }
        });
        Button logSession = new Button("⏱ Ghi buổi học");
        logSession.setOnAction(e -> logSession());
        Button delCourse = new Button("🗑");
        delCourse.setOnAction(e -> {
            Course c = courseTable.getSelectionModel().getSelectedItem();
            if (c != null && Dialogs.confirm("Xóa khóa học", "Xóa \"" + c.title + "\"?")) {
                ctx.learning().deleteCourse(c.id);
            }
        });

        // ---- flashcards
        deckList.setPrefWidth(220);
        deckList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Deck item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name + " ("
                        + ctx.learning().cardsInDeck(item.id).size() + " thẻ)");
            }
        });
        deckList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshCards());
        buildCardTable();
        Button addDeck = new Button("+ Bộ thẻ");
        addDeck.setOnAction(e -> {
            String name = Dialogs.prompt("Bộ thẻ mới", "Tên bộ thẻ:", "").orElse(null);
            if (name != null && !name.isBlank()) {
                ctx.learning().addDeck(name, "");
                refresh();
            }
        });
        Button addCard = new Button("+ Thẻ");
        addCard.setOnAction(e -> addCard());
        Button delCard = new Button("🗑");
        delCard.setOnAction(e -> {
            Flashcard f = cardTable.getSelectionModel().getSelectedItem();
            if (f != null && Dialogs.confirm("Xóa thẻ", "Xóa thẻ này?")) {
                ctx.learning().deleteCard(f.id);
                refresh();
            }
        });

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Khóa học", new VBox(10, new HBox(8, addCourse, editCourse, logSession, delCourse), courseTable)),
                new Tab("Flashcards", new HBox(10,
                        new VBox(8, addDeck, deckList),
                        new VBox(8, new HBox(8, addCard, delCard), cardTable))));
        HBox.setHgrow(deckList.getParent().getParent(), Priority.ALWAYS);

        ctx.learning().changes().addListener(() -> javafx.application.Platform.runLater(this::refresh));
        return new VBox(10, header, tabs);
    }

    private void buildCourseTable() {
        TableColumn<Course, String> titleCol = new TableColumn<>("Khóa học");
        titleCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().title));
        TableColumn<Course, String> statusCol = new TableColumn<>("Trạng thái");
        statusCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(courseStatus(p.getValue().status)));
        TableColumn<Course, String> progressCol = new TableColumn<>("Tiến độ");
        progressCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().progressPercent + "%"));
        TableColumn<Course, Long> minutesCol = new TableColumn<>("Phút học");
        minutesCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(ctx.learning().sessionsFor(p.getValue().id)
                .stream().mapToLong(s -> s.minutes).sum()));
        TableColumn<Course, String> noteCol = new TableColumn<>("Ghi chú liên kết");
        noteCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().linkedNoteIds.size() + " ghi chú"));
        courseTable.getColumns().setAll(List.of(titleCol, statusCol, progressCol, minutesCol, noteCol));
        courseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        courseTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && courseTable.getSelectionModel().getSelectedItem() != null) {
                openCourseEditor(courseTable.getSelectionModel().getSelectedItem());
            }
        });
    }

    private static String courseStatus(CourseStatus s) {
        return switch (s) {
            case PLANNED -> "Kế hoạch";
            case IN_PROGRESS -> "Đang học";
            case PAUSED -> "Tạm dừng";
            case COMPLETED -> "Hoàn thành";
        };
    }

    void openCourseEditor(Course existing) {
        javafx.scene.control.Dialog<Course> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(existing == null ? "Khóa học mới" : "Sửa khóa học");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);

        TextField title = new TextField(existing == null ? "" : existing.title);
        TextField source = new TextField(existing == null ? "" : existing.source);
        TextField link = new TextField(existing == null ? "" : existing.link);
        ComboBox<CourseStatus> status = new ComboBox<>();
        status.getItems().addAll(CourseStatus.values());
        status.setValue(existing == null ? CourseStatus.PLANNED : existing.status);
        Spinner<Integer> progress = new Spinner<>(0, 100, existing == null ? 0 : existing.progressPercent);
        ComboBox<com.lifedesk.notes.NoteMeta> notePick = new ComboBox<>();
        notePick.getItems().addAll(ctx.notes().list());
        notePick.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(com.lifedesk.notes.NoteMeta item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title);
            }
        });
        Label linkedInfo = new Label(existing == null ? "0 ghi chú liên kết"
                : existing.linkedNoteIds.size() + " ghi chú liên kết");

        Button toggleNote = new Button("Bật/tắt liên kết ghi chú đang chọn");
        toggleNote.setOnAction(e -> {
            if (existing != null && notePick.getValue() != null) {
                var noteId = notePick.getValue().id;
                if (existing.linkedNoteIds.contains(noteId)) {
                    ctx.learning().unlinkNote(existing.id, noteId);
                } else {
                    ctx.learning().linkNote(existing.id, noteId);
                }
                linkedInfo.setText(existing.linkedNoteIds.size() + " ghi chú liên kết");
            }
        });

        VBox content = new VBox(10,
                labeled("Tên *", title),
                new HBox(10, labeled("Nguồn (sách/site/mentor)", source), labeled("Đường dẫn tài liệu", link)),
                new HBox(10, labeled("Trạng thái", status), labeled("Tiến độ (%)", progress)),
                new HBox(10, labeled("Ghi chú học tập (Markdown)", notePick), new VBox(4, toggleNote, linkedInfo)));
        content.setPadding(new javafx.geometry.Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            Course c = existing == null ? new Course() : existing;
            c.title = title.getText();
            c.source = source.getText();
            c.link = link.getText();
            c.status = status.getValue();
            c.progressPercent = progress.getValue();
            return c;
        });
        dialog.showAndWait().ifPresent(c -> {
            try {
                if (existing == null) {
                    ctx.learning().addCourse(c.title, c.source, c.link, null);
                    ctx.learning().courses().stream().filter(x -> x.title.equals(c.title)).findFirst()
                            .ifPresent(created -> {
                                created.status = c.status;
                                created.progressPercent = c.progressPercent;
                                ctx.learning().updateCourse(created);
                            });
                } else {
                    ctx.learning().updateCourse(c);
                }
                refresh();
            } catch (RuntimeException ex) {
                Dialogs.error("Không lưu được", ex.getMessage());
            }
        });
    }

    private void logSession() {
        Course c = courseTable.getSelectionModel().getSelectedItem();
        if (c == null) {
            Dialogs.info("Chọn khóa học", "Hãy chọn một khóa học trước khi ghi buổi học.");
            return;
        }
        javafx.scene.control.Dialog<Boolean> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Ghi buổi học");
        dialog.setHeaderText(c.title);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        DatePicker date = new DatePicker(LocalDate.now());
        Spinner<Integer> minutes = new Spinner<>(5, 600, 30, 5);
        TextField topic = new TextField();
        dialog.getDialogPane().setContent(new VBox(8,
                labeled("Ngày", date), labeled("Số phút", minutes), labeled("Chủ đề", topic)));
        dialog.setResultConverter(bt -> bt.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        boolean[] ok = {false};
        dialog.showAndWait().ifPresent(v -> ok[0] = v);
        if (ok[0]) {
            try {
                ctx.learning().addSession(c.id, date.getValue(), minutes.getValue(), topic.getText());
                refresh();
            } catch (RuntimeException ex) {
                Dialogs.error("Không ghi được buổi học", ex.getMessage());
            }
        }
    }

    private void buildCardTable() {
        TableColumn<Flashcard, String> front = new TableColumn<>("Mặt trước");
        front.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().front));
        TableColumn<Flashcard, String> back = new TableColumn<>("Mặt sau");
        back.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().back));
        TableColumn<Flashcard, String> box = new TableColumn<>("Box");
        box.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(String.valueOf(p.getValue().box)));
        TableColumn<Flashcard, String> due = new TableColumn<>("Đến hạn");
        due.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().dueDate.toString()));
        TableColumn<Flashcard, String> stats = new TableColumn<>("Đúng/Sai");
        stats.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().timesCorrect + "/" + p.getValue().timesWrong));
        cardTable.getColumns().setAll(List.of(front, back, box, due, stats));
        cardTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void addCard() {
        Deck deck = deckList.getSelectionModel().getSelectedItem();
        if (deck == null) {
            Dialogs.info("Chọn bộ thẻ", "Hãy tạo/chọn một bộ thẻ trước.");
            return;
        }
        javafx.scene.control.Dialog<Flashcard> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Thẻ mới");
        dialog.setHeaderText(deck.name);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        TextArea front = new TextArea();
        front.setPromptText("Câu hỏi / mặt trước");
        front.setPrefRowCount(2);
        TextArea back = new TextArea();
        back.setPromptText("Đáp án / mặt sau");
        back.setPrefRowCount(2);
        dialog.getDialogPane().setContent(new VBox(8, new Label("Mặt trước *"), front, new Label("Mặt sau *"), back));
        dialog.setResultConverter(bt -> bt.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE
                ? new Flashcard() : null);
        dialog.showAndWait().ifPresent(card -> {
            try {
                ctx.learning().addCard(deck.id, front.getText(), back.getText());
                refresh();
            } catch (RuntimeException e) {
                Dialogs.error("Không thêm được thẻ", e.getMessage());
            }
        });
    }

    private void startReview() {
        var due = ctx.learning().dueCards(null);
        if (due.isEmpty()) {
            Dialogs.info("Ôn tập", "Không có thẻ nào đến hạn hôm nay. 🎉");
            return;
        }
        int total = due.size();
        int[] index = {0};
        boolean[] cancelled = {false};

        Label progressLabel = new Label();
        Label frontLabel = new Label();
        frontLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-wrap-text: true;");
        Label backLabel = new Label();
        backLabel.setStyle("-fx-wrap-text: true; -fx-opacity: 0.15;");

        javafx.scene.control.Dialog<javafx.scene.control.ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Ôn tập");
        dialog.setHeaderText(null);
        javafx.scene.control.ButtonType wrong = new javafx.scene.control.ButtonType("Sai ❌",
                javafx.scene.control.ButtonBar.ButtonData.NO);
        javafx.scene.control.ButtonType right = new javafx.scene.control.ButtonType("Đúng ✓",
                javafx.scene.control.ButtonBar.ButtonData.YES);
        dialog.getDialogPane().getButtonTypes().setAll(wrong, right, javafx.scene.control.ButtonType.CANCEL);
        VBox content = new VBox(12, progressLabel, frontLabel, backLabel);
        content.setPadding(new javafx.geometry.Insets(14));
        dialog.getDialogPane().setContent(content);

        while (index[0] < total && !cancelled[0]) {
            Flashcard card = due.get(index[0]);
            progressLabel.setText("Thẻ " + (index[0] + 1) + "/" + total + " — box hiện tại: " + card.box
                    + " (đáp án mờ ở dưới; tự trả lời trước rồi chọn Đúng/Sai)");
            frontLabel.setText(card.front);
            backLabel.setText("Đáp án: " + card.back);
            var pressedOpt = dialog.showAndWait();
            if (pressedOpt.isEmpty()) {
                cancelled[0] = true;
                break;
            }
            var pressed = pressedOpt.get();
            if (pressed.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE) {
                cancelled[0] = true;
                break;
            }
            boolean correct = pressed.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.YES;
            ctx.learning().review(card.id, correct);
            index[0]++;
        }
        if (!cancelled[0]) {
            Dialogs.info("Ôn tập xong", "Bạn đã ôn " + total + " thẻ. Lịch ôn tập tiếp theo đã được cập nhật.");
        }
        refresh();
    }

    private void refresh() {
        courseTable.getItems().setAll(ctx.learning().courses());
        var decks = ctx.learning().decks();
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        deckList.getItems().setAll(decks);
        if (selected != null) {
            decks.stream().filter(d -> d.id.equals(selected.id)).findFirst()
                    .ifPresent(deckList.getSelectionModel()::select);
        }
        refreshCards();
        dueLabel.setText("Thẻ đến hạn: " + ctx.learning().dueCount());
    }

    private void refreshCards() {
        Deck deck = deckList.getSelectionModel().getSelectedItem();
        cardTable.getItems().setAll(deck == null ? java.util.List.of() : ctx.learning().cardsInDeck(deck.id));
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void openRecord(String recordId) {
        courseTable.getItems().stream().filter(c -> c.id.equals(recordId)).findFirst()
                .ifPresent(courseTable.getSelectionModel()::select);
    }

    private VBox labeled(String text, Node field) {
        Label l = new Label(text);
        l.getStyleClass().add("dim-label");
        if (field instanceof TextField tf) {
            tf.setPrefWidth(280);
        }
        return new VBox(2, l, field);
    }
}
