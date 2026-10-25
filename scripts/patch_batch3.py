# -*- coding: utf-8 -*-
# Batch 3: theme wiring for dialogs + layout fill + Learning materials + AI model browser improvements.
import io

def patch(path, replacements):
    s = io.open(path, encoding='utf-8').read()
    for old, new in replacements:
        assert old in s, path + ' :: pattern not found :: ' + old[:70]
        s = s.replace(old, new, 1)
    io.open(path, 'w', encoding='utf-8').write(s)
    print('patched', path)

# ============ MainWindow: keep Dialogs stylesheets in sync with theme ============
patch('src/main/java/com/lifedesk/ui/MainWindow.java', [
    ('''    public void applyTheme() {
        scene.getStylesheets().setAll(ctx.themeManager().stylesheetsFor(ctx.config().theme));
    }''',
     '''    public void applyTheme() {
        scene.getStylesheets().setAll(ctx.themeManager().stylesheetsFor(ctx.config().theme));
        // Dialogs live in their own windows - hand them the active theme explicitly.
        Dialogs.setGlobalStylesheets(scene.getStylesheets());
    }'''),
])

# ============ HealthView: form fills the width ============
patch('src/main/java/com/lifedesk/ui/views/HealthView.java', [
    ('''        HBox formRow1 = new HBox(10,
                labeled("Ngày", entryDate),
                labeled("Giờ ngủ (0-24)", sleepField),
                labeled("Phút vận động", exerciseField),
                labeled("Cân nặng (kg)", weightField));''',
     '''        HBox formRow1 = new HBox(14,
                labeled("Ngày", entryDate),
                labeled("Giờ ngủ (0-24)", sleepField),
                labeled("Phút vận động", exerciseField),
                labeled("Cân nặng (kg)", weightField));
        formRow1.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        entryDate.setMaxWidth(Double.MAX_VALUE);
        sleepField.setMaxWidth(Double.MAX_VALUE);
        exerciseField.setMaxWidth(Double.MAX_VALUE);
        weightField.setMaxWidth(Double.MAX_VALUE);'''),
    ('''        HBox formRow2 = new HBox(10, labeled("Ghi chú sức khỏe", noteField), new VBox(2, new Label(""), save, delete));
        noteField.setPrefWidth(400);''',
     '''        HBox.setHgrow(formRow2.getChildren().get(0), Priority.ALWAYS);
        noteField.setMaxWidth(Double.MAX_VALUE);
        HBox formRow2 = new HBox(14, labeled("Ghi chú sức khỏe", noteField), new VBox(6, save, delete));'''),
])

# ============ LearningView: Course.materials + fill layout + flashcard improvements ============
patch('src/main/java/com/lifedesk/learning/Course.java', [
    ('''    /** Linked Markdown note ids. */
    public Set<String> linkedNoteIds = new LinkedHashSet<>();''',
     '''    /** Linked Markdown note ids. */
    public Set<String> linkedNoteIds = new LinkedHashSet<>();
    /** Local material files (lectures, slides, books...) managed by the Learning module. */
    public java.util.List<String> materialPaths = new java.util.ArrayList<>();'''),
])

patch('src/main/java/com/lifedesk/learning/LearningService.java', [
    ('''    public void unlinkNote(String courseId, String noteId) {
        Course c = course(courseId);
        c.linkedNoteIds.remove(noteId);
        persistCourses();
        change.fire();
    }''',
     '''    public void unlinkNote(String courseId, String noteId) {
        Course c = course(courseId);
        c.linkedNoteIds.remove(noteId);
        persistCourses();
        change.fire();
    }

    /** Register a material file for a course. */
    public void addMaterial(String courseId, String path) {
        Course c = course(courseId);
        if (!c.materialPaths.contains(path)) {
            c.materialPaths.add(path);
        }
        persistCourses();
        change.fire();
    }

    public void removeMaterial(String courseId, String path) {
        Course c = course(courseId);
        c.materialPaths.remove(path);
        persistCourses();
        change.fire();
    }

    public java.util.List<String> materials(String courseId) {
        return java.util.List.copyOf(course(courseId).materialPaths);
    }

    public int dueCountForDeck(String deckId) {
        LocalDate today = LocalDate.now();
        return (int) cards.stream()
                .filter(c -> c.deckId.equals(deckId) && !c.dueDate.isAfter(today))
                .count();
    }'''),
])

patch('src/main/java/com/lifedesk/ui/views/LearningView.java', [
    # courses tab fills width; materials table added
    ('''        buildSessionTable();
        VBox sessionsPane = new VBox(8, new HBox(8, logSession, editSession, delSession), sessionTable);
        VBox.setVgrow(sessionTable, Priority.ALWAYS);
        tabs.getTabs().addAll(
                new Tab("Khóa học", new VBox(10, new HBox(8, addCourse, editCourse, delCourse), courseTable, sessionsPane)),''',
     '''        buildSessionTable();
        buildMaterialTable();
        Button addMaterial = new Button("Thêm tài liệu…");
        addMaterial.setTooltip(new javafx.scene.control.Tooltip("Chọn file tài liệu (slide, sách, bài giảng…) gắn vào khóa"));
        addMaterial.setOnAction(e -> addMaterialFile());
        Button openMaterial = new Button("Mở tài liệu");
        openMaterial.setOnAction(e -> openSelectedMaterial());
        Button delMaterial = new Button("Bỏ tài liệu");
        delMaterial.setOnAction(e -> removeSelectedMaterial());
        VBox materialsPane = new VBox(8, new HBox(8, addMaterial, openMaterial, delMaterial), materialTable);
        VBox.setVgrow(materialTable, Priority.ALWAYS);
        VBox sessionsPane = new VBox(8, new HBox(8, logSession, editSession, delSession), sessionTable);
        VBox.setVgrow(sessionTable, Priority.ALWAYS);
        courseTable.setPrefHeight(260);
        materialTable.setPrefHeight(180);
        sessionTable.setPrefHeight(220);
        tabs.getTabs().addAll(
                new Tab("Khóa học & tài liệu", new VBox(10,
                        new HBox(8, addCourse, editCourse, delCourse),
                        courseTable,
                        materialsPane,
                        sessionsPane)),'''),

    ('''    private void buildSessionTable() {''',
     '''    private final TableView<String> materialTable = new TableView<>();

    private void buildMaterialTable() {
        TableColumn<String, String> pathCol = new TableColumn<>("Tài liệu của khóa đang chọn");
        pathCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        materialTable.getColumns().setAll(java.util.List.of(pathCol));
        materialTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        materialTable.setPlaceholder(new EmptyState("📄", "Chưa có tài liệu nào. Bấm \\"Thêm tài liệu…\\" để gắn file vào khóa."));
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
        chooser.setTitle("Chọn tài liệu cho \\"" + c.title + "\\"");
        File file = chooser.showOpenDialog(main.stage());
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

    private void buildSessionTable() {'''),

    # flashcards tab: fill width/height + per-deck due chips + review this deck
    ('''                new Tab("Flashcards", new HBox(10,
                        new VBox(8, new HBox(8, addDeck, renameDeck), deckList),
                        new VBox(8, new HBox(8, addCard, editCardBtn, delCard), cardTable))));''',
     '''                new Tab("Flashcards", new VBox(10,
                        new HBox(8, addDeck, renameDeck, reviewDeckBtn),
                        new HBox(10,
                                new VBox(8, deckList, deckStats),
                                new VBox(8, new HBox(8, addCard, editCardBtn, delCard), cardTable))));'''),

    ('''        deckList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshCards());''',
     '''        deckList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshCards());
        deckList.setPrefHeight(400);
        deckList.setPrefWidth(280);
        VBox.setVgrow(deckList, Priority.ALWAYS);
        deckStats.getStyleClass().add("dim-label");'''),

    ('''        Button delCard = new Button("🗑");''',
     '''        Button reviewDeckBtn = new Button("Ôn tập bộ đang chọn");
        reviewDeckBtn.getStyleClass().add("primary-btn");
        reviewDeckBtn.setOnAction(e -> startReview());
        deckStats = new Label();
        Button delCard = new Button("🗑");'''),

    ('''    private void refreshCards() {
        Deck deck = deckList.getSelectionModel().getSelectedItem();
        cardTable.getItems().setAll(deck == null ? java.util.List.of() : ctx.learning().cardsInDeck(deck.id));
    }''',
     '''    private final Label deckStats = new Label();

    private void refreshCards() {
        Deck deck = deckList.getSelectionModel().getSelectedItem();
        cardTable.getItems().setAll(deck == null ? java.util.List.of() : ctx.learning().cardsInDeck(deck.id));
        if (deck == null) {
            deckStats.setText("Chọn hoặc tạo một bộ thẻ để bắt đầu.");
        } else {
            int total = ctx.learning().cardsInDeck(deck.id).size();
            int due = ctx.learning().dueCountForDeck(deck.id);
            deckStats.setText(deck.name + ": " + total + " thẻ, " + due + " đến hạn ôn tập hôm nay.");
        }
        HBox.setHgrow(cardTable.getParent() == null ? cardTable : cardTable, Priority.ALWAYS);
        cardTable.setPrefHeight(500);
    }'''),

    # review() start: respect selected deck when present
    ('''    private void startReview() {
        var due = ctx.learning().dueCards(null);''',
     '''    private void startReview() {
        Deck selectedDeck = deckList.getSelectionModel().getSelectedItem();
        var due = ctx.learning().dueCards(selectedDeck == null ? null : selectedDeck.id);'''),
])

# fix the review flow to re-seed stats after review; refresh() already refreshes cards.
print('batch3 applied')
