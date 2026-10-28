package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.notes.NoteMeta;
import com.lifedesk.notes.SaveResult;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.IconView;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Comparator;
import java.util.LinkedHashSet;

/**
 * Notes & Knowledge: markdown editor + live preview, folders, tags, autosave
 * with conflict detection. The editor keeps the loaded-content hash so
 * external edits are never silently overwritten.
 */
public class NotesView extends View {
    private final ComboBox<String> folderPick = new ComboBox<>();
    private boolean updatingFolders;
    private final ListView<NoteMeta> noteList = new ListView<>();
    private final TextField searchField = new TextField();
    private final TextField titleField = new TextField();
    private final TextField tagsField = new TextField();
    private final TextArea editor = new TextArea();
    private final com.lifedesk.markdown.MarkdownPreviewPane preview = new com.lifedesk.markdown.MarkdownPreviewPane();
    private final Label statusHint = new Label("");
    private final BorderPane editorPane = new BorderPane();
    private final PauseTransition autosave = new PauseTransition(Duration.seconds(1.5));
    private HBox editorBar;

    private NoteMeta current;
    private String loadedHash = "";
    private boolean previewMode = true;
    private String currentFolder = "";

    public NotesView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "NOTES";
    }

    @Override
    protected Node build() {
        // ---- left: folders + list + search
        // A full folder list wasted half the column on two rows; a compact picker
        // leaves the vertical space to the note list.
        folderPick.setMaxWidth(Double.MAX_VALUE);
        folderPick.getItems().add("(tất cả)");
        folderPick.getSelectionModel().select(0);
        folderPick.valueProperty().addListener((o, a, b) -> {
            if (updatingFolders) {
                return;
            }
            currentFolder = "(tất cả)".equals(b) || b == null ? "" : b;
            refreshNoteList();
        });
        Label folderLabel = new Label("Thư mục");
        folderLabel.getStyleClass().add("form-label");
        HBox folderRow = new HBox(10, folderLabel, folderPick);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(folderPick, Priority.ALWAYS);

        noteList.setPrefWidth(270);
        noteList.getStyleClass().add("note-list");
        noteList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(NoteMeta item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    int linkCount = item.links.values().stream().mapToInt(java.util.Set::size).sum();
                    Label title = new Label(item.title
                            + (linkCount > 0 ? " (" + linkCount + " liên kết)" : ""));
                    if (item.starred) {
                        title.setGraphic(new IconView("star", 14));
                    }
                    Label meta = new Label(item.folder.isEmpty() ? "(gốc)" : item.folder);
                    meta.getStyleClass().add("dim-label");
                    setGraphic(new VBox(1, title, meta));
                    setText(null);
                }
            }
        });
        noteList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> openNote(b));

        searchField.setPromptText("Tìm ghi chú…");
        searchField.textProperty().addListener((o, a, b) -> refreshNoteList());

        Button newBtn = new Button("Ghi chú mới", new IconView("plus", 15));
        addAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), newBtn::fire);
        newBtn.getStyleClass().add("primary-btn");
        newBtn.setOnAction(e -> createNote());
        Button delBtn = new Button("", new IconView("trash", 15));
        delBtn.getStyleClass().add("icon-btn");
        delBtn.setTooltip(new Tooltip("Xóa ghi chú đang chọn"));
        delBtn.setOnAction(e -> deleteCurrent());
        Button starBtn = new Button("", new IconView("star", 15));
        starBtn.getStyleClass().add("icon-btn");
        starBtn.setTooltip(new Tooltip("Đánh dấu quan trọng"));
        starBtn.setOnAction(e -> toggleStar());

        HBox listActions = new HBox(8, newBtn, starBtn, delBtn);
        listActions.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(10, searchField, folderRow, noteList, listActions);
        VBox.setVgrow(noteList, Priority.ALWAYS);
        left.setMinWidth(300);
        left.setPrefWidth(340);
        searchField.setMinWidth(0);
        SplitPane.setResizableWithParent(left, Boolean.FALSE);

        // ---- right: editor + preview
        titleField.setPromptText("Tiêu đề ghi chú");
        tagsField.setPromptText("tags, phân cách bằng dấu phẩy");
        titleField.textProperty().addListener((o, a, b) -> queueAutosave());
        tagsField.textProperty().addListener((o, a, b) -> queueAutosave());
        editor.setPromptText("Viết markdown ở đây… heading, bảng, danh sách, checklist (- [ ]), code block, [[liên kết ghi chú]]");
        editor.textProperty().addListener((o, a, b) -> {
            queueAutosave();
            if (previewMode) {
                renderPreview();
            }
        });

        Button saveBtn = new Button("Lưu ngay", new IconView("save", 15));
        saveBtn.setOnAction(e -> saveNow());
        addAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), saveBtn::fire);
        ToggleButton previewToggle = new ToggleButton("Xem trước", new IconView("eye", 15));
        previewToggle.setSelected(true);
        previewToggle.setOnAction(e -> {
            previewMode = previewToggle.isSelected();
            layoutEditorSplit();
        });
        statusHint.getStyleClass().add("dim-label");

        editorBar = new HBox(8, titleField, new Region(), tagsField, saveBtn, previewToggle);
        editorBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleField, Priority.ALWAYS);

        editorPane.setTop(editorBar);
        layoutEditorSplit();

        preview.setHandlers(
                this::openRecord,
                url -> {
                    if (Dialogs.confirm("Mở liên kết ngoài",
                            "Mở đường dẫn này bằng trình duyệt?\n" + url)) {
                        try {
                            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                        } catch (Exception ex) {
                            Dialogs.error("Lỗi", "Không mở được liên kết: " + ex.getMessage());
                        }
                    }
                });

        editorPane.setBottom(statusHint);
        // Pre-render an empty themed page so the preview never flashes white.
        preview.render("", ctx.themeManager().previewPalette(ctx.config().theme));

        // Two panes in an HBox instead of a SplitPane: the split's skin inset the
        // children differently and its divider stopped above the bottom edge.
        left.setMinWidth(300);
        left.setPrefWidth(320);
        left.setMaxWidth(320);
        left.setMaxHeight(Double.MAX_VALUE);
        editorPane.setMaxHeight(Double.MAX_VALUE);
        HBox root = new HBox(16, left, editorPane);
        HBox.setHgrow(editorPane, Priority.ALWAYS);
        showNotesEmptyState();
        return root;
    }

    private final com.lifedesk.ui.components.EmptyState noNoteState =
            new com.lifedesk.ui.components.EmptyState("sticky-note", "Chọn hoặc tạo một ghi chú để bắt đầu.");

    private void showNotesEmptyState() {
        editorPane.setTop(null);
        editorPane.setCenter(noNoteState);
        editorPane.setBottom(null);
    }

    private void layoutEditorSplit() {
        if (previewMode) {
            SplitPane split = new SplitPane(editor, preview);
            split.setDividerPositions(0.5);
            split.setMaxWidth(Double.MAX_VALUE);
            split.setMaxHeight(Double.MAX_VALUE);
            editorPane.setCenter(split);
            renderPreview();
        } else {
            editorPane.setCenter(editor);
        }
    }

    private void renderPreview() {
        preview.render(editor.getText(), ctx.themeManager().previewPalette(ctx.config().theme),
                target -> ctx.notes().resolveLink(target).map(s -> "lifedesk://note/" + s).orElse(null));
    }

    // ------------------------------------------------------------------ list

    private void refreshFolders() {
        String current = folderPick.getValue();
        updatingFolders = true;
        folderPick.getItems().setAll("(tất cả)");
        // blank folder = root; shown as "(gốc)" instead of an empty row
        ctx.notes().folders().stream()
                .filter(f -> !f.isBlank())
                .forEach(folderPick.getItems()::add);
        folderPick.setValue(current != null && folderPick.getItems().contains(current)
                ? current : "(tất cả)");
        updatingFolders = false;
    }

    private void refreshNoteList() {
        String q = searchField.getText();
        var notes = ctx.notes().search(q).stream()
                .filter(n -> currentFolder.isEmpty() || n.folder.equals(currentFolder))
                .sorted(Comparator.comparing((NoteMeta n) -> n.updatedAt).reversed())
                .toList();
        noteList.getItems().setAll(notes);
        refreshFolders();
    }

    private void openNote(NoteMeta meta) {
        if (meta == current) {
            return; // selection re-fired by our own refresh; nothing to do
        }
        saveNow(); // flush previous note before switching
        current = meta;
        if (meta == null) {
            showNotesEmptyState();
            return;
        }
        editorPane.setTop(editorBar);
        editorPane.setBottom(statusHint);
        layoutEditorSplit();
        String content = ctx.notes().readContent(meta.id);
        loadedHash = com.lifedesk.common.Fingerprint.sha256(content);
        editor.setText(content);
        titleField.setText(meta.title);
        tagsField.setText(String.join(", ", meta.tags));
        String links = ctx.notes().linksSummary(meta.id);
        statusHint.setText("Đã mở \"" + meta.title + "\" — tự động lưu sau 1.5s ngừng gõ."
                + (links.isBlank() ? "" : "  Liên kết: " + links));
        renderPreview();
    }

    private void queueAutosave() {
        if (current == null) {
            return;
        }
        statusHint.setText("Đang soạn… (tự động lưu)");
        autosave.stop();
        autosave.setOnFinished(e -> saveNow());
        autosave.playFromStart();
    }

    private void saveNow() {
        if (current == null) {
            return;
        }
        try {
            SaveResult result = ctx.notes().saveContent(current.id, editor.getText(), loadedHash);
            String titleNew = titleField.getText();
            var tags = new LinkedHashSet<String>();
            for (String t : tagsField.getText().split(",")) {
                if (!t.isBlank()) {
                    tags.add(t.trim());
                }
            }
            boolean metaChanged = (!titleNew.isBlank() && !titleNew.equals(current.title))
                    || !tags.equals(current.tags);
            if (metaChanged) {
                ctx.notes().updateMeta(current, titleNew, current.folder, tags, current.starred);
            }
            if (result == SaveResult.SAVED_WITH_CONFLICT_COPY) {
                Dialogs.warn("Phát hiện xung đột",
                        "Ghi chú \"" + current.title + "\" đã bị sửa bên ngoài ứng dụng.\n"
                                + "Bản bên ngoài được giữ lại thành file -conflict-*.md; nội dung bạn vừa lưu là bản mới nhất.");
            }
            if (result != SaveResult.UNCHANGED || metaChanged) {
                statusHint.setText("Đã lưu lúc " + java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
                loadedHash = com.lifedesk.common.Fingerprint.sha256(editor.getText());
            }
        } catch (RuntimeException e) {
            statusHint.setText("LƯU THẤT BẠI: " + e.getMessage());
            Dialogs.error("Lưu ghi chú thất bại", e.getMessage()
                    + "\nNội dung vẫn còn trong trình soạn thảo — thử lại hoặc chép ra nơi khác.");
        }
        refreshNoteListKeepingSelection();
    }

    private void refreshNoteListKeepingSelection() {
        String q = searchField.getText();
        var notes = ctx.notes().search(q).stream()
                .filter(n -> currentFolder.isEmpty() || n.folder.equals(currentFolder))
                .sorted(Comparator.comparing((NoteMeta n) -> n.updatedAt).reversed())
                .toList();
        NoteMeta selected = noteList.getSelectionModel().getSelectedItem();
        noteList.getItems().setAll(notes);
        if (selected != null) {
            notes.stream().filter(n -> n.id.equals(selected.id)).findFirst()
                    .ifPresent(n -> noteList.getSelectionModel().select(n));
        }
    }

    private void createNote() {
        String title = Dialogs.prompt("Ghi chú mới", "Tiêu đề ghi chú:", "").orElse(null);
        if (title == null || title.isBlank()) {
            return;
        }
        NoteMeta meta = ctx.notes().create(title, currentFolder, "# " + title + "\n\n", new LinkedHashSet<>());
        refreshNoteList();
        noteList.getSelectionModel().select(meta);
    }

    private void deleteCurrent() {
        if (current == null) {
            return;
        }
        if (Dialogs.confirm("Xóa ghi chú", "Xóa vĩnh viễn \"" + current.title + "\"?")) {
            ctx.notes().delete(current.id);
            current = null;
            editor.clear();
            showNotesEmptyState();
            refreshNoteList();
        }
    }

    private void toggleStar() {
        if (current != null) {
            ctx.notes().updateMeta(current, null, null, null, !current.starred);
            refreshNoteListKeepingSelection();
        }
    }

    @Override
    public void onShow() {
        refreshNoteList();
    }

    @Override
    public void onHide() {
        saveNow();
    }

    @Override
    public void openRecord(String recordId) {
        ctx.notes().list().stream().filter(n -> n.id.equals(recordId)).findFirst()
                .ifPresent(meta -> {
                    editorPane.setVisible(true);
                    refreshNoteList();
                    noteList.getSelectionModel().select(meta);
                });
    }
}
