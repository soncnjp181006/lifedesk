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
    private final javafx.scene.control.TreeView<String> folderTree = new javafx.scene.control.TreeView<>();
    private final HBox mdToolbar = new HBox(2);

    private NoteMeta current;
    /** Folder shown in the note list ("" = every note). */
    private String currentFolder = "";
    private boolean updatingTree;
    private String loadedHash = "";
    private boolean previewMode = true;

    public NotesView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "NOTES";
    }

    @Override
    protected Node build() {
        // ---- left: list + search
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

        Button moveBtn = new Button("", new IconView("folder", 15));
        moveBtn.getStyleClass().add("icon-btn");
        moveBtn.setTooltip(new Tooltip("Chuyển ghi chú sang thư mục khác"));
        moveBtn.setOnAction(e -> moveCurrentToFolder());
        HBox listActions = new HBox(8, newBtn, starBtn, moveBtn, delBtn);
        listActions.setAlignment(Pos.CENTER_LEFT);

        // Folder tree (nested paths) with create / rename / delete, vault-style.
        folderTree.setCellFactory(tv -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item.isEmpty() ? "(tất cả)" : item.substring(item.lastIndexOf('/') + 1));
                    setTooltip(item.isEmpty() ? null : new Tooltip(item));
                }
            }
        });
        folderTree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (updatingTree) {
                return;
            }
            currentFolder = b == null ? "" : b.getValue();
            refreshNoteList();
        });
        folderTree.setMinHeight(90);
        folderTree.setMaxHeight(260);

        Button newFolderBtn = new Button("", new IconView("plus", 15));
        newFolderBtn.getStyleClass().add("icon-btn");
        newFolderBtn.setTooltip(new Tooltip("Tạo thư mục con trong thư mục đang chọn"));
        newFolderBtn.setOnAction(e -> createFolder());
        Button renameFolderBtn = new Button("", new IconView("pencil", 15));
        renameFolderBtn.getStyleClass().add("icon-btn");
        renameFolderBtn.setTooltip(new Tooltip("Đổi tên thư mục đang chọn"));
        renameFolderBtn.setOnAction(e -> renameFolder());
        Button deleteFolderBtn = new Button("", new IconView("trash", 15));
        deleteFolderBtn.getStyleClass().add("icon-btn");
        deleteFolderBtn.setTooltip(new Tooltip("Xoá thư mục và toàn bộ ghi chú bên trong"));
        deleteFolderBtn.setOnAction(e -> deleteFolder());
        Label folderHeading = new Label("Thư mục");
        folderHeading.getStyleClass().add("form-label");
        Region folderSpacer = new Region();
        HBox.setHgrow(folderSpacer, Priority.ALWAYS);
        HBox folderToolbar = new HBox(6, folderHeading, folderSpacer,
                newFolderBtn, renameFolderBtn, deleteFolderBtn);
        folderToolbar.setAlignment(Pos.CENTER_LEFT);

        VBox left = new VBox(10, searchField, folderToolbar, folderTree, noteList, listActions);
        left.getStyleClass().add("notes-sidebar");
        VBox.setVgrow(noteList, Priority.ALWAYS);
        left.setMinWidth(300);
        left.setPrefWidth(340);
        searchField.setMinWidth(0);
        searchField.getStyleClass().add("notes-search-field");
        SplitPane.setResizableWithParent(left, Boolean.FALSE);

        // ---- right: editor + preview
        titleField.setPromptText("Tiêu đề ghi chú");
        titleField.getStyleClass().add("notes-title-field");
        tagsField.setPromptText("tags, phân cách bằng dấu phẩy");
        tagsField.getStyleClass().add("notes-tag-field");
        titleField.textProperty().addListener((o, a, b) -> queueAutosave());
        tagsField.textProperty().addListener((o, a, b) -> queueAutosave());
        editor.getStyleClass().add("notes-editor");
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
        editorBar.getStyleClass().add("notes-editor-bar");
        editorBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleField, Priority.ALWAYS);

        buildMarkdownToolbar();
        editorPane.getStyleClass().add("notes-editor-pane");
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
        HBox root = new HBox(10, left, editorPane);
        root.getStyleClass().add("notes-shell");
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
        VBox editorStack = new VBox(8, mdToolbar, editor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        if (previewMode) {
            SplitPane split = new SplitPane(editorStack, preview);
            split.setDividerPositions(0.5);
            split.setMaxWidth(Double.MAX_VALUE);
            split.setMaxHeight(Double.MAX_VALUE);
            editorPane.setCenter(split);
            renderPreview();
        } else {
            editorPane.setCenter(editorStack);
        }
    }

    private void renderPreview() {
        preview.render(editor.getText(), ctx.themeManager().previewPalette(ctx.config().theme),
                target -> ctx.notes().resolveLink(target).map(s -> "lifedesk://note/" + s).orElse(null));
    }

    // ------------------------------------------------------------------ list

    private void refreshNoteList() {
        String q = searchField.getText();
        var notes = ctx.notes().search(q).stream()
                .filter(n -> currentFolder.isEmpty() || currentFolder.equals(n.folder))
                .sorted(Comparator.comparing((NoteMeta n) -> n.updatedAt).reversed())
                .toList();
        noteList.getItems().setAll(notes);
        refreshFolders();
    }

    private void refreshFolders() {
        updatingTree = true;
        String target = currentFolder;
        javafx.scene.control.TreeItem<String> root = new javafx.scene.control.TreeItem<>("");
        root.setExpanded(true);
        java.util.LinkedHashMap<String, javafx.scene.control.TreeItem<String>> byPath =
                new java.util.LinkedHashMap<>();
        for (String path : ctx.notes().listFolders()) {
            int cut = path.lastIndexOf('/');
            String parentPath = cut < 0 ? "" : path.substring(0, cut);
            javafx.scene.control.TreeItem<String> parent = parentPath.isEmpty()
                    ? root : byPath.get(parentPath);
            javafx.scene.control.TreeItem<String> node = new javafx.scene.control.TreeItem<>(path);
            node.setExpanded(true);
            (parent == null ? root : parent).getChildren().add(node);
            byPath.put(path, node);
        }
        folderTree.setRoot(root);
        // Fit the tree to its rows; a fixed height left a large empty block above the notes.
        folderTree.setPrefHeight(Math.min(260, 40 + (byPath.size() + 1) * 27));
        javafx.scene.control.TreeItem<String> match = byPath.get(target);
        folderTree.getSelectionModel().select(match == null ? root : match);
        updatingTree = false;
    }

    private void createFolder() {
        String name = Dialogs.prompt("Thư mục mới", "Tên thư mục:", "").orElse(null);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            String path = currentFolder.isEmpty() ? name.trim() : currentFolder + "/" + name.trim();
            currentFolder = ctx.notes().createFolder(path);
            refreshFolders();
            refreshNoteList();
            main.status("Đã tạo thư mục " + currentFolder);
        } catch (RuntimeException e) {
            Dialogs.error("Không tạo được thư mục", e.getMessage());
        }
    }

    private void renameFolder() {
        if (currentFolder.isEmpty()) {
            Dialogs.info("Chọn thư mục", "Chọn một thư mục (không phải \"(tất cả)\") để đổi tên.");
            return;
        }
        String leaf = currentFolder.substring(currentFolder.lastIndexOf('/') + 1);
        String name = Dialogs.prompt("Đổi tên thư mục", "Tên mới cho \"" + leaf + "\":", leaf).orElse(null);
        if (name == null || name.isBlank() || name.trim().equals(leaf)) {
            return;
        }
        try {
            currentFolder = ctx.notes().renameFolder(currentFolder, name.trim());
            refreshFolders();
            refreshNoteList();
            main.status("Đã đổi tên thành " + currentFolder);
        } catch (RuntimeException e) {
            Dialogs.error("Không đổi tên được", e.getMessage());
        }
    }

    private void deleteFolder() {
        if (currentFolder.isEmpty()) {
            Dialogs.info("Chọn thư mục", "Chọn một thư mục để xoá.");
            return;
        }
        String target = currentFolder;
        int count = ctx.notes().countNotesInFolder(target);
        if (!Dialogs.confirm("Xoá thư mục", "Xoá \"" + target + "\""
                + (count > 0 ? " và toàn bộ " + count + " ghi chú bên trong?" : "?"))) {
            return;
        }
        if (count > 0 && !Dialogs.confirm("Xoá vĩnh viễn?",
                count + " ghi chú trong thư mục này sẽ bị xoá và không thể khôi phục.")) {
            return;
        }
        try {
            ctx.notes().deleteFolder(target);
            currentFolder = target.contains("/")
                    ? target.substring(0, target.lastIndexOf('/')) : "";
            refreshFolders();
            refreshNoteList();
            main.status("Đã xoá thư mục " + target);
        } catch (RuntimeException e) {
            Dialogs.error("Không xoá được thư mục", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ markdown toolbar

    private void buildMarkdownToolbar() {
        mdToolbar.getStyleClass().add("md-toolbar");
        mdToolbar.setAlignment(Pos.CENTER_LEFT);
        mdToolbar.getChildren().addAll(
                mdButton("B", "Đậm", "-fx-font-weight: bold;", () -> wrapSelection("**", "**", "đậm")),
                mdButton("I", "Nghiêng", "-fx-font-style: italic;", () -> wrapSelection("*", "*", "nghiêng")),
                mdButton("S", "Gạch ngang", "-fx-strikethrough: true;", () -> wrapSelection("~~", "~~", "gạch")),
                mdButton("H1", "Tiêu đề 1", "", () -> prefixSelectedLines("# ")),
                mdButton("H2", "Tiêu đề 2", "", () -> prefixSelectedLines("## ")),
                mdButton("H3", "Tiêu đề 3", "", () -> prefixSelectedLines("### ")),
                mdButton("•", "Danh sách chấm", "", () -> prefixSelectedLines("- ")),
                mdButton("1.", "Danh sách số", "", () -> prefixSelectedLines("1. ")),
                mdButton("☐", "Checklist", "", () -> prefixSelectedLines("- [ ] ")),
                mdButton("\"", "Trích dẫn", "", () -> prefixSelectedLines("> ")),
                mdButton("`x`", "Code inline", "", () -> wrapSelection("`", "`", "code")),
                mdButton("{ }", "Khối code", "", () -> insertBlock("\n```\ncode\n```\n")),
                mdButton("[…]", "Liên kết", "", () -> wrapSelection("[", "](https://)", "mô tả")),
                mdButton("▦", "Bảng 2 cột", "",
                        () -> insertBlock("\n| Cột 1 | Cột 2 |\n| --- | --- |\n|  |  |\n")));
    }

    private Button mdButton(String label, String tip, String inlineStyle, Runnable action) {
        Button b = new Button(label);
        b.getStyleClass().add("ghost-btn");
        b.getStyleClass().add("md-toolbar-btn");
        if (!inlineStyle.isEmpty()) {
            b.setStyle(inlineStyle);
        }
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private void wrapSelection(String prefix, String suffix, String placeholder) {
        javafx.scene.control.IndexRange sel = editor.getSelection();
        String selected = sel.getLength() > 0
                ? editor.getText().substring(sel.getStart(), sel.getEnd()) : placeholder;
        editor.replaceText(sel.getStart(), sel.getEnd(), prefix + selected + suffix);
        editor.selectRange(sel.getStart() + prefix.length(),
                sel.getStart() + prefix.length() + selected.length());
        editor.requestFocus();
    }

    private void prefixSelectedLines(String prefix) {
        javafx.scene.control.IndexRange sel = editor.getSelection();
        String text = editor.getText();
        int from = sel.getLength() > 0 ? sel.getStart()
                : text.lastIndexOf('\n', Math.max(0, editor.getCaretPosition() - 1)) + 1;
        int to = sel.getLength() > 0 ? sel.getEnd() : text.indexOf('\n', editor.getCaretPosition());
        if (to < 0) {
            to = text.length();
        }
        String[] lines = text.substring(from, to).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(prefix).append(lines[i]);
        }
        editor.replaceText(from, to, sb.toString());
        editor.selectRange(from, from + sb.length());
        editor.requestFocus();
    }

    private void insertBlock(String block) {
        int pos = editor.getCaretPosition();
        editor.insertText(pos, block);
        editor.positionCaret(pos + block.length());
        editor.requestFocus();
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
                .filter(n -> currentFolder.isEmpty() || currentFolder.equals(n.folder))
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

    private void moveCurrentToFolder() {
        if (current == null) {
            Dialogs.info("Chưa chọn ghi chú", "Chọn một ghi chú trước khi chuyển thư mục.");
            return;
        }
        java.util.List<String> options = new java.util.ArrayList<>();
        options.add("(gốc)");
        options.addAll(ctx.notes().listFolders());
        String currentLabel = current.folder.isEmpty() ? "(gốc)" : current.folder;
        javafx.scene.control.ChoiceDialog<String> dialog =
                new javafx.scene.control.ChoiceDialog<>(currentLabel, options);
        dialog.initOwner(main.stage());
        dialog.setTitle("Chuyển ghi chú");
        dialog.setHeaderText("Thư mục đích cho \"" + current.title + "\":");
        dialog.setContentText("Thư mục");
        dialog.showAndWait().ifPresent(choice -> {
            String target = "(gốc)".equals(choice) ? "" : choice;
            if (target.equals(current.folder)) {
                return;
            }
            try {
                ctx.notes().updateMeta(current, null, target, null, current.starred);
                refreshNoteList();
                main.status("Đã chuyển \"" + current.title + "\" sang " + choice);
            } catch (RuntimeException e) {
                Dialogs.error("Không chuyển được", e.getMessage());
            }
        });
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
        refreshFolders();
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
