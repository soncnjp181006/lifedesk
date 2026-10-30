package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.learning.Course;
import com.lifedesk.learning.CourseStatus;
import com.lifedesk.learning.Deck;
import com.lifedesk.learning.Flashcard;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.FormGrid;
import com.lifedesk.ui.components.IconView;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
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

import java.util.List;

/** Learning: courses with study sessions, decks of flashcards with Leitner review. */
public class LearningView extends View {
    private static final java.time.format.DateTimeFormatter DF =
            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final TableView<Course> courseTable = new TableView<>();
    private final TableView<Flashcard> cardTable = new TableView<>();
    private final ListView<Deck> deckList = new ListView<>();
    /** Reference for jumping to the review tab from header buttons. */
    private javafx.scene.control.TabPane tabPane;
    private com.lifedesk.ui.panes.ReviewSessionPane reviewPane;
    private Button reviewBtn;

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
        reviewBtn = new Button("Ôn tập (" + ctx.learning().dueCount() + " thẻ đến hạn)", new IconView("target", 15));
        reviewBtn.getStyleClass().add("primary-btn");
        reviewBtn.setOnAction(e -> startReview());
        HBox header = new HBox(12, title, spacer, reviewBtn);
        header.getStyleClass().add("view-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- courses
        buildCourseTable();
        courseTable.setPlaceholder(new EmptyState("library-big", "Chưa có khóa học nào."));
        Button addCourse = new Button("Khóa học", new IconView("plus", 15));
        addCourse.setOnAction(e -> openCourseEditor(null));
        Button editCourse = new Button("Sửa", new IconView("pencil", 15));
        editCourse.setOnAction(e -> {
            Course c = courseTable.getSelectionModel().getSelectedItem();
            if (c != null) {
                openCourseEditor(c);
            }
        });
        Button delCourse = new Button("Xóa khóa");
        delCourse.setOnAction(e -> {
            Course c = courseTable.getSelectionModel().getSelectedItem();
            if (c != null && Dialogs.confirm("Xóa khóa học", "Xóa \"" + c.title + "\"?")) {
                ctx.learning().deleteCourse(c.id);
            }
        });

        // ---- flashcards
        deckList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Deck item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name + " ("
                        + ctx.learning().cardsInDeck(item.id).size() + " thẻ)");
            }
        });
        deckList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshCards());
        deckList.setPrefHeight(200);
        VBox.setVgrow(deckList, Priority.ALWAYS);
        deckStats.getStyleClass().add("dim-label");
        buildCardTable();
        Button addDeck = new Button("Bộ thẻ", new IconView("plus", 15));
        addDeck.setOnAction(e -> {
            String name = Dialogs.prompt("Bộ thẻ mới", "Tên bộ thẻ:", "").orElse(null);
            if (name != null && !name.isBlank()) {
                ctx.learning().addDeck(name, "");
                refresh();
            }
        });
        Button renameDeck = new Button("Đổi tên");
        renameDeck.setOnAction(e -> {
            Deck d = deckList.getSelectionModel().getSelectedItem();
            if (d == null) {
                return;
            }
            String name = Dialogs.prompt("Đổi tên bộ thẻ", "Tên mới cho \"" + d.name + "\":", d.name).orElse(null);
            if (name != null && !name.isBlank()) {
                ctx.learning().renameDeck(d.id, name);
                refresh();
            }
        });
        Button addCard = new Button("Thẻ", new IconView("plus", 15));
        addCard.setOnAction(e -> addCard());
        javafx.scene.control.Button editCardBtn = new javafx.scene.control.Button("Sửa thẻ");
        editCardBtn.setOnAction(e -> editSelectedCard());
        Button reviewDeckBtn = new Button("Ôn tập", new IconView("target", 15));
        reviewDeckBtn.getStyleClass().add("primary-btn");
        reviewDeckBtn.setTooltip(new Tooltip("Ôn tập các thẻ đến hạn của bộ đang chọn"));
        reviewDeckBtn.setOnAction(e -> startReview());
        Button delCard = new Button("", new IconView("trash", 15));
        delCard.getStyleClass().add("icon-btn");
        delCard.setTooltip(new Tooltip("Xóa thẻ"));
        delCard.setOnAction(e -> {
            Flashcard f = cardTable.getSelectionModel().getSelectedItem();
            if (f != null && Dialogs.confirm("Xóa thẻ", "Xóa thẻ này?")) {
                ctx.learning().deleteCard(f.id);
                refresh();
            }
        });

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        buildMaterialTable();
        Button addMaterial = new Button("Thêm tài liệu…");
        addMaterial.setTooltip(new javafx.scene.control.Tooltip("Chọn file tài liệu (slide, sách, bài giảng…) gắn vào khóa"));
        addMaterial.setOnAction(e -> addMaterialFile());
        Button openMaterial = new Button("Mở tài liệu");
        openMaterial.setOnAction(e -> openSelectedMaterial());
        Button delMaterial = new Button("Bỏ tài liệu");
        delMaterial.setOnAction(e -> removeSelectedMaterial());
        HBox materialToolbar = new HBox(10, addMaterial, openMaterial, delMaterial);
        materialToolbar.setAlignment(Pos.CENTER_LEFT);
        VBox materialsPane = new VBox(10, materialToolbar, materialTable);
        VBox.setVgrow(materialTable, Priority.ALWAYS);
        // Fixed heights (260/180/220) pushed the session table below the fold; let the
        // three tables share the viewport instead.
        courseTable.setPrefHeight(200);
        courseTable.setMinHeight(120);
        materialTable.setPrefHeight(170);
        materialTable.setMinHeight(90);
        VBox.setVgrow(courseTable, Priority.ALWAYS);
        VBox.setVgrow(materialsPane, Priority.ALWAYS);

        // Both columns are structurally IDENTICAL (fixed-height toolbar + growing
        // content), so their tops and bottoms always align. The deck-stats line
        // used to sit under the left list, lifting that panel's bottom above the
        // table's; it now runs full-width beneath both columns.
        HBox deckToolbar = new HBox(8, addDeck, renameDeck, reviewDeckBtn);
        deckToolbar.setAlignment(Pos.CENTER_LEFT);
        deckToolbar.setMinHeight(34);
        deckList.setPlaceholder(new Label("Chưa có bộ thẻ nào."));
        VBox deckPane = new VBox(10, deckToolbar, deckList);
        deckPane.setMinWidth(280);
        deckPane.setPrefWidth(280);
        HBox cardToolbar = new HBox(8, addCard, editCardBtn, delCard);
        cardToolbar.setAlignment(Pos.CENTER_LEFT);
        cardToolbar.setMinHeight(34);
        cardTable.setPlaceholder(new EmptyState("list",
                "Chọn một bộ thẻ bên trái — hoặc thêm thẻ mới."));
        cardTable.setPrefHeight(240);
        VBox cardPane = new VBox(10, cardToolbar, cardTable);
        VBox.setVgrow(cardTable, Priority.ALWAYS);
        HBox.setHgrow(cardPane, Priority.ALWAYS);
        HBox flashcardsRow = new HBox(16, deckPane, cardPane);
        VBox.setVgrow(flashcardsRow, Priority.ALWAYS);
        VBox flashcardsTab = new VBox(8, flashcardsRow, deckStats);
        deckStats.setWrapText(false);

        HBox courseToolbar = new HBox(10, addCourse, editCourse, delCourse);
        courseToolbar.setAlignment(Pos.CENTER_LEFT);
        // Review session tab first — it is the daily entry point of the module.
        reviewPane = new com.lifedesk.ui.panes.ReviewSessionPane(ctx);
        tabPane = tabs;
        tabs.getTabs().addAll(
                new Tab("Ôn tập", reviewPane),
                new Tab("Khóa học & tài liệu", new VBox(14, courseToolbar, courseTable, materialsPane)),
                new Tab("Flashcards", flashcardsTab));

        ctx.learning().changes().addListener(this::scheduleRefresh);
        return new VBox(10, header, tabs);
    }

    private void buildCourseTable() {
        TableColumn<Course, String> titleCol = new TableColumn<>("Khóa học");
        titleCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().title));
        TableColumn<Course, String> statusCol = new TableColumn<>("Trạng thái");
        statusCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(courseStatus(p.getValue().status)));
        TableColumn<Course, String> progressCol = new TableColumn<>("Tiến độ");
        progressCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().progressPercent + "%"));
        TableColumn<Course, String> noteCol = new TableColumn<>("Ghi chú liên kết");
        noteCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().linkedNoteIds.size() + " ghi chú"));
        courseTable.getColumns().setAll(List.of(titleCol, statusCol, progressCol, noteCol));
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
        javafx.scene.control.Dialog<Course> dialog = Dialogs.newDialog();
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
        // Vietnamese names in the list and the button — raw "PLANNED" read as a bug.
        javafx.util.Callback<javafx.scene.control.ListView<CourseStatus>, ListCell<CourseStatus>> statusCell =
                v -> new ListCell<>() {
                    @Override
                    protected void updateItem(CourseStatus item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : courseStatus(item));
                    }
                };
        status.setCellFactory(statusCell);
        status.setButtonCell(statusCell.call(null));
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
                    // addCourse returns the new course; set status/progress on it
                    // instead of re-scanning the list by title.
                    Course created = ctx.learning().addCourse(c.title, c.source, c.link, null);
                    created.status = c.status;
                    created.progressPercent = c.progressPercent;
                    ctx.learning().updateCourse(created);
                } else {
                    ctx.learning().updateCourse(c);
                }
                refresh();
            } catch (RuntimeException ex) {
                Dialogs.error("Không lưu được", ex.getMessage());
            }
        });
    }

    private final TableView<String> materialTable = new TableView<>();

    private void buildMaterialTable() {
        TableColumn<String, String> pathCol = new TableColumn<>("Tài liệu của khóa đang chọn");
        pathCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        materialTable.getColumns().setAll(java.util.List.of(pathCol));
        materialTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        materialTable.setPlaceholder(new EmptyState("file-text", "Chưa có tài liệu nào. Bấm \"Thêm tài liệu…\" để gắn file vào khóa."));
        materialTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openSelectedMaterial();
            }
        });
        courseTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshMaterials());
    }

    private void refreshMaterials() {
        Course c = courseTable.getSelectionModel().getSelectedItem();
        materialTable.getItems().setAll(c == null ? java.util.List.of() : ctx.learning().materials(c.id));
    }

    private void addMaterialFile() {
        Course c = courseTable.getSelectionModel().getSelectedItem();
        if (c == null) {
            Dialogs.info("Chọn khóa học", "Hãy chọn khóa học trước khi thêm tài liệu.");
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Chọn tài liệu cho \"" + c.title + "\"");
        java.io.File file = chooser.showOpenDialog(main.stage());
        if (file == null) {
            return;
        }
        ctx.learning().addMaterial(c.id, file.getAbsolutePath());
        refreshMaterials();
    }

    private void openSelectedMaterial() {
        String path = materialTable.getSelectionModel().getSelectedItem();
        if (path == null) {
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(new java.io.File(path));
        } catch (Exception ex) {
            Dialogs.error("Không mở được tài liệu", ex.getMessage());
        }
    }

    private void removeSelectedMaterial() {
        Course c = courseTable.getSelectionModel().getSelectedItem();
        String path = materialTable.getSelectionModel().getSelectedItem();
        if (c == null || path == null) {
            return;
        }
        ctx.learning().removeMaterial(c.id, path);
        refreshMaterials();
    }

    private void buildCardTable() {
        TableColumn<Flashcard, String> front = new TableColumn<>("Mặt trước");
        front.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().front));
        TableColumn<Flashcard, String> back = new TableColumn<>("Mặt sau");
        back.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().back));
        TableColumn<Flashcard, String> box = new TableColumn<>("Box");
        box.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(String.valueOf(p.getValue().box)));
        TableColumn<Flashcard, String> due = new TableColumn<>("Đến hạn");
        due.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().dueDate.format(DF)));
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
        javafx.scene.control.Dialog<Flashcard> dialog = Dialogs.newDialog();
        dialog.setTitle("Thẻ mới");
        dialog.setHeaderText(deck.name);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        // A card can be a whole page of notes: wide dialog, tall wrapping areas —
        // the old 2-row boxes forced vertical AND horizontal scrolling.
        javafx.scene.control.TextArea front = new TextArea();
        front.setPromptText("Câu hỏi / mặt trước");
        front.setPrefRowCount(8);
        front.setWrapText(true);
        javafx.scene.control.TextArea back = new TextArea();
        back.setPromptText("Đáp án / mặt sau");
        back.setPrefRowCount(8);
        back.setWrapText(true);
        VBox fields = new VBox(8,
                new VBox(2, new Label("Mặt trước *"), front),
                new VBox(2, new Label("Mặt sau *"), back));
        fields.setPrefWidth(620);
        dialog.getDialogPane().setContent(fields);
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

    private void editSelectedCard() {
        Flashcard card = cardTable.getSelectionModel().getSelectedItem();
        if (card == null) {
            Dialogs.info("Chọn thẻ", "Hãy chọn một thẻ trong bảng để sửa.");
            return;
        }
        javafx.scene.control.Dialog<Boolean> dialog = Dialogs.newDialog();
        dialog.setTitle("Sửa thẻ");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.control.TextArea front = new javafx.scene.control.TextArea(card.front);
        front.setPrefRowCount(8);
        front.setWrapText(true);
        javafx.scene.control.TextArea back = new javafx.scene.control.TextArea(card.back);
        back.setPrefRowCount(8);
        back.setWrapText(true);
        VBox editFields = new VBox(8,
                new VBox(2, new Label("Mặt trước *"), front),
                new VBox(2, new Label("Mặt sau *"), back));
        editFields.setPrefWidth(620);
        dialog.getDialogPane().setContent(editFields);
        dialog.setResultConverter(bt -> bt.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        boolean[] ok = {false};
        dialog.showAndWait().ifPresent(v -> ok[0] = v);
        if (ok[0]) {
            try {
                ctx.learning().updateCard(card.id, front.getText(), back.getText());
                refresh();
            } catch (RuntimeException e) {
                Dialogs.error("Không sửa được thẻ", e.getMessage());
            }
        }
    }

    private void startReview() {
        // The review session lives in its own tab (card flip + LaTeX rendering);
        // jump there instead of the old modal dialog.
        if (tabPane != null) {
            tabPane.getSelectionModel().select(0);
        }
    }

    @Override
    protected void refresh() {
        Course keepCourse = courseTable.getSelectionModel().getSelectedItem();
        var courses = ctx.learning().courses();
        courseTable.getItems().setAll(courses);
        // Materials/sessions are scoped to the selected course, so an empty selection
        // made an account with data look empty. Keep the current row if it still
        // exists, otherwise fall back to the first one.
        Course restored = keepCourse == null ? null
                : courses.stream().filter(c -> c.id.equals(keepCourse.id)).findFirst().orElse(null);
        if (restored != null) {
            courseTable.getSelectionModel().select(restored);
        } else {
            courseTable.getSelectionModel().selectFirst();
        }

        var decks = ctx.learning().decks();
        Deck selected = deckList.getSelectionModel().getSelectedItem();
        deckList.getItems().setAll(decks);
        Deck keepDeck = selected == null ? null
                : decks.stream().filter(d -> d.id.equals(selected.id)).findFirst().orElse(null);
        if (keepDeck != null) {
            deckList.getSelectionModel().select(keepDeck);
        } else {
            deckList.getSelectionModel().selectFirst();
        }
        refreshCards();
        int due = ctx.learning().dueCount();
        reviewBtn.setText("Ôn tập (" + due + " thẻ đến hạn)");
    }

    @Override
    public void onThemeChanged() {
        if (reviewPane != null) {
            reviewPane.retheme();
        }
    }

    private final Label deckStats = new Label();

    private void refreshCards() {
        Deck deck = deckList.getSelectionModel().getSelectedItem();
        cardTable.getItems().setAll(deck == null ? java.util.List.of() : ctx.learning().cardsInDeck(deck.id));
        if (deck == null) {
            deckStats.setText("");
        } else {
            int total = ctx.learning().cardsInDeck(deck.id).size();
            int due = ctx.learning().dueCountForDeck(deck.id);
            deckStats.setText(deck.name + ": " + total + " thẻ, " + due + " đến hạn ôn tập hôm nay.");
        }
    }

    @Override
    public void onShow() {
        // Only re-read when the module actually changed since the last paint.
        refreshWhenChanged(ctx.learning().changes());
    }

    @Override
    public void openRecord(String recordId) {
        courseTable.getItems().stream().filter(c -> c.id.equals(recordId)).findFirst()
                .ifPresent(courseTable.getSelectionModel()::select);
    }

    /** Dialog fields read better at a fixed width; structure shared via FormGrid. */
    private VBox labeled(String text, Node field) {
        if (field instanceof TextField tf) {
            tf.setPrefWidth(280);
        }
        return FormGrid.labeled(text, field);
    }
}
