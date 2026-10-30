package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.notes.NoteMeta;
import com.lifedesk.notes.SaveResult;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.common.Tags;
import com.lifedesk.markdown.MarkdownEditor;
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
import javafx.scene.control.TreeItem;
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
import java.util.List;

/**
 * Notes & Knowledge: markdown editor + live preview, folders, tags, autosave
 * with conflict detection. The editor keeps the loaded-content hash so
 * external edits are never silently overwritten.
 */
public class NotesView extends View {
    private final TextField searchField = new TextField();
    private final TextField titleField = new TextField();
    private final TextField tagsField = new TextField();
    private final TextArea editor = new TextArea();
    private final com.lifedesk.markdown.MarkdownPreviewPane preview = new com.lifedesk.markdown.MarkdownPreviewPane();
    private final Label statusHint = new Label("");
    private final BorderPane editorPane = new BorderPane();
    private final PauseTransition autosave = new PauseTransition(Duration.seconds(1.5));
    /**
     * Debounce for the note-search box: search reads every matching note's full
     * content, so firing per keystroke made typing feel heavy on large vaults.
     */
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(250));
    private HBox editorBar;
    /** Full-width header (tab row + toolbar) spanning editor and preview. */
    private VBox headerStack;
    /** Debounced preview + outline rendering — a full WebView rebuild per keystroke caused visible lag. */
    private final javafx.animation.PauseTransition livePanels =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(280));
    /** Unified explorer tree: folders AND notes nested together (file-manager style). */
    private final javafx.scene.control.TreeView<TreeEntry> folderTree = new javafx.scene.control.TreeView<>();
    /** Note currently being dragged onto a folder (drag & drop move). */
    private NoteMeta draggingNote;
    /** First build expands every folder; later builds honor the user's collapses. */
    private boolean treeStateInitialized;

    /** One row of the explorer tree: either a folder path or a note. */
    record TreeEntry(String folderPath, NoteMeta note) {
        boolean isFolder() {
            return note == null;
        }

        static TreeEntry folder(String path) {
            return new TreeEntry(path, null);
        }

        static TreeEntry note(NoteMeta meta) {
            return new TreeEntry(meta.folder, meta);
        }
    }
    private final HBox mdToolbar = new HBox(2);
    /** Quiet-outline side panel (quiet-outline port). */
    private final com.lifedesk.ui.panes.NoteOutlinePane outlinePane =
            new com.lifedesk.ui.panes.NoteOutlinePane(pos -> {
                editor.positionCaret(Math.min(pos, editor.getText().length()));
                editor.requestFocus();
            });
    private final ToggleButton outlineToggle = new ToggleButton("Outline", new IconView("list", 15));
    /** LaTeX typing assistant on/off (shorthand + auto-fraction + auto-$). */
    private final ToggleButton latexToggle = new ToggleButton("LaTeX", new IconView("sigma", 15));
    private volatile List<com.lifedesk.markdown.LatexEngine.Snippet> shorthandCache = List.of();
    /** Pinned media player (media notes); null when the note has no media link. */
    private com.lifedesk.ui.panes.MediaBar mediaBar;

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
        // ---- left: unified explorer tree (folders contain their notes)

        searchField.setPromptText("Tìm…");
        searchField.textProperty().addListener((o, a, b) -> queueSearchRefresh());

        Button newBtn = new Button("", new IconView("plus", 15));
        addAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), newBtn::fire);
        newBtn.getStyleClass().add("icon-btn");
        newBtn.setTooltip(new Tooltip("Ghi chú mới (Ctrl+N)"));
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
        Region listSpring = new Region();
        HBox.setHgrow(listSpring, Priority.ALWAYS);
        HBox listActions = new HBox(2, listSpring, starBtn, moveBtn, delBtn);
        listActions.setAlignment(Pos.CENTER_RIGHT);

        // Folder tree (nested paths) with create / rename / delete, vault-style.
        folderTree.setShowRoot(false);
        folderTree.setCellFactory(tv -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(TreeEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setOnDragDetected(null);
                    setOnDragOver(null);
                    setOnDragDropped(null);
                    return;
                }
                if (item.isFolder()) {
                    String path = item.folderPath();
                    String leaf = path.isEmpty() ? "Tất cả ghi chú" : path.substring(path.lastIndexOf('/') + 1);
                    setText(leaf);
                    setGraphic(new IconView("folder", 14, "icon-dim"));
                    setTooltip(path.isEmpty() ? null : new Tooltip(path));
                    setStyle("-fx-font-weight: bold;");
                    // Explorer behavior: click a folder label toggles it open/closed.
                    setOnMouseClicked(e -> {
                        if (e.getClickCount() == 1) {
                            TreeItem<TreeEntry> ti = getTreeItem();
                            ti.setExpanded(!ti.isExpanded());
                        }
                    });
                    // Drop a dragged note on this folder to move it there.
                    setOnDragOver(e -> {
                        if (draggingNote != null && !draggingNote.folder.equals(item.folderPath())) {
                            e.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                            e.consume();
                        }
                    });
                    setOnDragDropped(e -> {
                        NoteMeta moved = draggingNote;
                        draggingNote = null;
                        if (moved != null) {
                            ctx.notes().updateMeta(moved, moved.title, item.folderPath(),
                                    moved.tags, moved.starred);
                            moved.folder = item.folderPath();
                            if (current != null && current.id.equals(moved.id)) {
                                current = moved;
                            }
                            refreshNoteList();
                        }
                        e.setDropCompleted(true);
                        e.consume();
                    });
                } else {
                    NoteMeta n = item.note();
                    setText(n.title);
                    setGraphic(new IconView("file-text", 13, "icon-dim"));
                    setTooltip(null);
                    setStyle("");
                    // Drag a note onto a folder to move it (real file-manager feel).
                    setOnDragDetected(e -> {
                        draggingNote = n;
                        var db = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                        var cc = new javafx.scene.input.ClipboardContent();
                        cc.putString(n.id);
                        db.setContent(cc);
                        e.consume();
                    });
                    setOnDragOver(null);
                    setOnDragDropped(null);
                }
            }
        });
        folderTree.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (updatingTree || b == null) {
                return;
            }
            if (b.getValue().isFolder()) {
                currentFolder = b.getValue().folderPath();
            } else {
                openNote(b.getValue().note());
            }
        });

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
        HBox searchRow = new HBox(4, searchField, newBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        HBox folderToolbar = new HBox(2, new Region(), newFolderBtn, renameFolderBtn, deleteFolderBtn);
        Region folderSpring = new Region();
        HBox.setHgrow(folderSpring, Priority.ALWAYS);
        folderToolbar.getChildren().set(0, folderSpring);

        VBox left = new VBox(8, searchRow, folderToolbar, folderTree, listActions);
        left.getStyleClass().add("notes-sidebar");
        VBox.setVgrow(folderTree, Priority.ALWAYS);
        left.setMinWidth(280);
        left.setPrefWidth(300);
        searchField.setMinWidth(0);
        searchField.getStyleClass().add("notes-search-field");
        SplitPane.setResizableWithParent(left, Boolean.FALSE);

        // ---- right: editor + preview
        titleField.setPromptText("Tiêu đề");
        titleField.getStyleClass().add("notes-title-field");
        tagsField.setPromptText("tags…");
        tagsField.getStyleClass().add("notes-tag-field");
        titleField.textProperty().addListener((o, a, b) -> queueAutosave());
        tagsField.textProperty().addListener((o, a, b) -> queueAutosave());
        editor.getStyleClass().add("notes-editor");
        editor.setPromptText("");
        editor.textProperty().addListener((o, a, b) -> {
            queueAutosave();
            queueLivePanels();
        });
        installLatexAssistant();

        Button saveBtn = new Button("", new IconView("save", 15));
        saveBtn.getStyleClass().add("icon-btn");
        saveBtn.setTooltip(new Tooltip("Lưu ngay (Ctrl+S)"));
        saveBtn.setOnAction(e -> saveNow());
        addAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), saveBtn::fire);
        ToggleButton previewToggle = new ToggleButton("", new IconView("eye", 15));
        previewToggle.getStyleClass().add("icon-btn");
        previewToggle.setSelected(true);
        previewToggle.setTooltip(new Tooltip("Xem trước"));
        previewToggle.setOnAction(e -> {
            previewMode = previewToggle.isSelected();
            layoutEditorSplit();
        });
        outlineToggle.setText("");
        outlineToggle.setTooltip(new Tooltip("Outline đề mục"));
        outlineToggle.selectedProperty().addListener((o, a, b) -> layoutEditorSplit());
        latexToggle.setText("∑");
        latexToggle.setTooltip(new Tooltip("Trợ gõ LaTeX"));
        latexToggle.setSelected(ctx.config().latexAssist);
        latexToggle.setOnAction(e -> ctx.config().latexAssist = latexToggle.isSelected());
        statusHint.getStyleClass().add("dim-label");

        Button mediaBtn = new Button("", new IconView("puzzle", 15));
        mediaBtn.getStyleClass().add("icon-btn");
        mediaBtn.setTooltip(new Tooltip("Media note (Ctrl+I chèn timestamp)"));
        mediaBtn.setOnAction(e -> editMediaLink());

        // Tab row: tên ghi chú + tags + chế độ xem —Obsidian-style header.
        Region tabSpring = new Region();
        HBox.setHgrow(tabSpring, Priority.ALWAYS);
        HBox tabRow = new HBox(8, titleField, tabSpring, tagsField, previewToggle);
        tabRow.getStyleClass().add("notes-tab-row");
        tabRow.setAlignment(Pos.CENTER_LEFT);

        // Heading levels Ctrl+1..6 (editing-toolbar convention).
        for (int i = 1; i <= 6; i++) {
            final int level = i;
            addAccelerator(new KeyCodeCombination(KeyCode.valueOf("DIGIT" + i), KeyCombination.CONTROL_DOWN),
                    () -> MarkdownEditor.prefixSelectedLines(editor, "#".repeat(level) + " "));
        }
        // Media-notes timestamp hotkey (media-notes convention: note without leaving the text).
        addAccelerator(new KeyCodeCombination(KeyCode.I, KeyCombination.CONTROL_DOWN),
                () -> {
                    if (mediaBar != null && mediaBar.hasPlayer()) {
                        mediaBar.insertTimestamp();
                    }
                });

        buildMarkdownToolbar();
        Region toolbarSpring = new Region();
        HBox.setHgrow(toolbarSpring, Priority.ALWAYS);
        HBox actionRow = new HBox(2, mediaBtn, latexToggle, outlineToggle, saveBtn);
        actionRow.setAlignment(Pos.CENTER_RIGHT);
        mdToolbar.getChildren().add(0, toolbarSpring);
        mdToolbar.getChildren().add(actionRow);
        HBox.setHgrow(actionRow, Priority.NEVER);
        this.headerStack = new VBox(tabRow, mdToolbar);
        this.editorBar = tabRow; // legacy reference (empty-state guard)
        editorPane.getStyleClass().add("notes-editor-pane");
        editorPane.setTop(headerStack);
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
            new com.lifedesk.ui.components.EmptyState("sticky-note", "Chưa có ghi chú.");

    private void showNotesEmptyState() {
        editorPane.setTop(null);
        editorPane.setCenter(noNoteState);
        editorPane.setBottom(null);
    }

    private void layoutEditorSplit() {
        BorderPane editorHost = new BorderPane();
        editorHost.setCenter(editor);
        if (mediaBar != null && current != null && !current.mediaLink.isBlank()) {
            // media-notes layout: player pinned on top, note content scrolls below.
            editorHost.setTop(mediaBar);
        }
        if (outlineToggle.isSelected()) {
            outlinePane.setPrefWidth(230);
            outlinePane.setMinWidth(170);
            outlinePane.getStyleClass().add("outline-pane");
            editorHost.setRight(outlinePane);
        }
        VBox editorStack = new VBox(editorHost);
        VBox.setVgrow(editorHost, Priority.ALWAYS);
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

    /** Debounced preview + outline rebuild (perf: no WebView repaint per keystroke). */
    private void queueLivePanels() {
        livePanels.stop();
        livePanels.setOnFinished(e -> {
            if (previewMode) {
                renderPreview();
            }
            outlinePane.update(current == null ? "" : current.id, editor.getText());
        });
        livePanels.playFromStart();
    }

    /** Debounced search: wait for a typing pause before scanning note contents. */
    private void queueSearchRefresh() {
        searchDebounce.stop();
        searchDebounce.setOnFinished(e -> refreshNoteList());
        searchDebounce.playFromStart();
    }

    /**
     * Rebuild the unified explorer tree: folders nested by path with their
     * notes INSIDE them (real file-manager layout). Search text switches the
     * tree to a flat results list. Expansion state survives rebuilds.
     */
    private void refreshNoteList() {
        updatingTree = true;
        boolean firstBuild = !treeStateInitialized;
        java.util.Set<String> expanded = collectExpandedFolders(folderTree.getRoot());

        String q = searchField.getText();
        boolean searching = q != null && !q.isBlank();
        var notes = ctx.notes().search(q).stream()
                .sorted(Comparator.comparing((NoteMeta n) -> n.title.toLowerCase())).toList();

        javafx.scene.control.TreeItem<TreeEntry> root =
                new javafx.scene.control.TreeItem<>(TreeEntry.folder(""));
        root.setExpanded(true);
        if (searching) {
            for (NoteMeta n : notes) {
                root.getChildren().add(new javafx.scene.control.TreeItem<>(TreeEntry.note(n)));
            }
        } else {
            java.util.Map<String, javafx.scene.control.TreeItem<TreeEntry>> byPath =
                    new java.util.LinkedHashMap<>();
            byPath.put("", root);
            for (String path : ctx.notes().listFolders()) {
                int cut = path.lastIndexOf('/');
                String parentPath = cut < 0 ? "" : path.substring(0, cut);
                javafx.scene.control.TreeItem<TreeEntry> parent =
                        byPath.getOrDefault(parentPath, root);
                javafx.scene.control.TreeItem<TreeEntry> node =
                        new javafx.scene.control.TreeItem<>(TreeEntry.folder(path));
                node.setExpanded(firstBuild || expanded.contains(path));
                parent.getChildren().add(node);
                byPath.put(path, node);
            }
            for (NoteMeta n : notes) {
                byPath.getOrDefault(n.folder, root).getChildren()
                        .add(new javafx.scene.control.TreeItem<>(TreeEntry.note(n)));
            }
        }
        folderTree.setRoot(root);
        treeStateInitialized = true;

        // Keep the open note selected after the rebuild.
        if (current != null) {
            selectNoteInTree(root, current.id);
        }
        updatingTree = false;
    }

    /** Find and select the note's row (expands ancestors); selection listener opens it. */
    private void selectNoteInTree(String noteId) {
        if (folderTree.getRoot() != null) {
            javafx.scene.control.TreeItem<TreeEntry> item = findNoteItem(folderTree.getRoot(), noteId);
            if (item != null) {
                folderTree.getSelectionModel().select(item);
            }
        }
    }

    private javafx.scene.control.TreeItem<TreeEntry> findNoteItem(
            javafx.scene.control.TreeItem<TreeEntry> node, String noteId) {
        for (javafx.scene.control.TreeItem<TreeEntry> child : node.getChildren()) {
            if (!child.getValue().isFolder() && child.getValue().note().id.equals(noteId)) {
                return child;
            }
            javafx.scene.control.TreeItem<TreeEntry> deep = findNoteItem(child, noteId);
            if (deep != null) {
                return deep;
            }
        }
        return null;
    }

    private boolean selectNoteInTree(javafx.scene.control.TreeItem<TreeEntry> node, String noteId) {
        for (javafx.scene.control.TreeItem<TreeEntry> child : node.getChildren()) {
            if (!child.getValue().isFolder() && child.getValue().note().id.equals(noteId)) {
                folderTree.getSelectionModel().select(child);
                return true;
            }
            if (child.getValue().isFolder() && selectNoteInTree(child, noteId)) {
                child.setExpanded(true);
                return true;
            }
        }
        return false;
    }

    private java.util.Set<String> collectExpandedFolders(javafx.scene.control.TreeItem<TreeEntry> node) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (node != null) {
            for (javafx.scene.control.TreeItem<TreeEntry> child : node.getChildren()) {
                if (child.getValue().isFolder() && child.isExpanded()) {
                    out.add(child.getValue().folderPath());
                    out.addAll(collectExpandedFolders(child));
                }
            }
        }
        return out;
    }


    private void createFolder() {
        String name = Dialogs.prompt("Thư mục mới", "Tên thư mục:", "").orElse(null);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            String path = currentFolder.isEmpty() ? name.trim() : currentFolder + "/" + name.trim();
            currentFolder = ctx.notes().createFolder(path);
            refreshNoteList();
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
            refreshNoteList();
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
            refreshNoteList();
            refreshNoteList();
            main.status("Đã xoá thư mục " + target);
        } catch (RuntimeException e) {
            Dialogs.error("Không xoá được thư mục", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ LaTeX assistant

    /**
     * Typing assistant (latex-suite + quick-latex essence), active while the
     * "LaTeX" toggle is on:
     * <ul>
     *   <li>{@code /} inside math → auto-fraction on the preceding operand</li>
     *   <li>space → shorthand expansion from the user table (trigger ends at caret)</li>
     *   <li>{@code $} in text mode → auto-close pair with caret inside</li>
     *   <li>Tab inside math → jump past the closing delimiter</li>
     * </ul>
     */
    private void installLatexAssistant() {
        reloadShorthands();
        editor.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, e -> {
            if (!latexToggle.isSelected()) {
                return;
            }
            String one = e.getCharacter();
            if (one == null || one.isEmpty()) {
                return;
            }
            int caret = editor.getCaretPosition();
            String text = editor.getText();
            try {
                switch (one) {
                    case "/" -> {
                        if (com.lifedesk.markdown.LatexEngine.insideMath(text, caret)) {
                            var exp = com.lifedesk.markdown.LatexEngine.autoFraction(text, caret);
                            if (exp != null) {
                                editor.replaceText(exp.from(), caret, exp.replacement());
                                editor.positionCaret(exp.from() + exp.caretInReplacement());
                                e.consume();
                            }
                        }
                    }
                    case " " -> {
                        var exp = com.lifedesk.markdown.LatexEngine.matchShorthand(
                                text, caret, shorthandCache);
                        if (exp != null) {
                            editor.replaceText(exp.from(), caret, exp.replacement());
                            editor.positionCaret(exp.from() + exp.caretInReplacement());
                            e.consume();
                        }
                    }
                    case "$" -> {
                        boolean escaped = caret > 0 && text.charAt(caret - 1) == '\\';
                        if (!escaped && !com.lifedesk.markdown.LatexEngine.insideMath(text, caret)) {
                            // open a math span: $$ with the caret between the dollars
                            editor.replaceText(caret, caret, "$$");
                            editor.positionCaret(caret + 1);
                            e.consume();
                        }
                    }
                    default -> {
                        // no assist for other characters
                    }
                }
            } catch (RuntimeException ex) {
                // Never let an assist bug break typing; fall back to normal input.
            }
        });
        editor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (!latexToggle.isSelected() || e.getCode() != KeyCode.TAB) {
                return;
            }
            int caret = editor.getCaretPosition();
            String text = editor.getText();
            // Tab expansion first (latex-suite style), then exit-math.
            var exp = com.lifedesk.markdown.LatexEngine.matchShorthand(text, caret, shorthandCache);
            if (exp != null) {
                editor.replaceText(exp.from(), caret, exp.replacement());
                editor.positionCaret(exp.from() + exp.caretInReplacement());
                e.consume();
                return;
            }
            int exit = com.lifedesk.markdown.LatexEngine.mathExitPosition(text, caret);
            if (exit >= 0) {
                editor.positionCaret(exit);
                e.consume();
            }
        });
    }

    /** Reload the shorthand table from config (user-editable, fallback to defaults). */
    private void reloadShorthands() {
        String table = ctx.config().latexShorthands;
        List<com.lifedesk.markdown.LatexEngine.Snippet> parsed =
                com.lifedesk.markdown.LatexEngine.parseShorthands(
                        table == null || table.isBlank()
                                ? com.lifedesk.markdown.LatexEngine.defaultShorthandTable()
                                : table + "\n" + com.lifedesk.markdown.LatexEngine.defaultShorthandTable());
        shorthandCache = parsed;
    }

    // ------------------------------------------------------------------ markdown toolbar

    private void buildMarkdownToolbar() {
        mdToolbar.getStyleClass().add("md-toolbar");
        mdToolbar.setAlignment(Pos.CENTER_LEFT);
        mdToolbar.getChildren().addAll(
                mdButton("B", "Đậm (Ctrl+B vùng chọn không bắt buộc)", "-fx-font-weight: bold;",
                        () -> MarkdownEditor.wrapSelection(editor, "**", "**", "đậm")),
                mdButton("I", "Nghiêng", "-fx-font-style: italic;",
                        () -> MarkdownEditor.wrapSelection(editor, "*", "*", "nghiêng")),
                mdButton("S", "Gạch ngang", "-fx-strikethrough: true;",
                        () -> MarkdownEditor.wrapSelection(editor, "~~", "~~", "gạch")),
                mdButton("≡", "Đánh dấu nổi bật (==highlight==)", "",
                        () -> MarkdownEditor.wrapSelection(editor, "==", "==", "nổi bật")),
                sep(),
                mdButton("H1", "Tiêu đề 1 (Ctrl+1)", "", () -> MarkdownEditor.prefixSelectedLines(editor, "# ")),
                mdButton("H2", "Tiêu đề 2 (Ctrl+2)", "", () -> MarkdownEditor.prefixSelectedLines(editor, "## ")),
                mdButton("H3", "Tiêu đề 3 (Ctrl+3)", "", () -> MarkdownEditor.prefixSelectedLines(editor, "### ")),
                headingMenu(),
                sep(),
                mdButton("•", "Danh sách chấm", "", () -> MarkdownEditor.prefixSelectedLines(editor, "- ")),
                mdButton("1.", "Danh sách số", "", () -> MarkdownEditor.prefixSelectedLines(editor, "1. ")),
                mdButton("☐", "Checklist", "", () -> MarkdownEditor.prefixSelectedLines(editor, "- [ ] ")),
                mdButton("\"", "Trích dẫn", "", () -> MarkdownEditor.prefixSelectedLines(editor, "> ")),
                sep(),
                mdButton("`x`", "Code inline", "", () -> MarkdownEditor.wrapSelection(editor, "`", "`", "code")),
                mdButton("{ }", "Khối code", "", () -> MarkdownEditor.insertBlock(editor, "\n```\ncode\n```\n")),
                mdButton("$x$", "Công thức inline", "", () -> MarkdownEditor.wrapSelection(editor, "$", "$", "x^2")),
                mdButton("$$", "Khối công thức", "", () -> MarkdownEditor.insertBlock(editor, "\n$$\n\\frac{a}{b}\n$$\n")),
                sep(),
                mdButton("[…]", "Liên kết", "", () -> MarkdownEditor.wrapSelection(editor, "[", "](https://)", "mô tả")),
                mdButton("[[+]]", "Liên kết ghi chú", "", () -> MarkdownEditor.wrapSelection(editor, "[[", "]]", "Tên ghi chú")),
                mdButton("▦", "Bảng 2 cột", "",
                        () -> MarkdownEditor.insertBlock(editor,
                                "\n| Cột 1 | Cột 2 |\n| --- | --- |\n|  |  |\n")),
                mdButton("―", "Đường kẻ ngang", "", () -> MarkdownEditor.insertBlock(editor, "\n---\n")),
                sep(),
                mdButton("🖼", "Thẻ link từ clipboard (lấy tiêu đề + ảnh)", "", this::insertLinkCard),
                mdButton("🧠", "Mindmap từ outline của ghi chú", "", this::showMindmap),
                sep(),
                mdButton("⋮≡", "Outline", "", outlineToggle::fire));
    }

    /**
     * Thumbnails pattern: paste a URL from the clipboard → fetch its title and
     * og:image → insert a rich link card in markdown. Network is used only on
     * this explicit action.
     */
    private void insertLinkCard() {
        String raw = null;
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit()
                    .getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            raw = data == null ? null : data.toString();
        } catch (Exception e) {
            // clipboard unavailable or unreadable — treated as empty below
        }
        final String url = raw == null ? "" : raw.trim();
        if (!url.startsWith("http")) {
            main.status("Clipboard không chứa URL http(s).");
            return;
        }
        main.status("Đang lấy thông tin liên kết…");
        Fx.async(ctx.executors().io(),
                () -> {
                    try {
                        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(url)
                                .userAgent("LifeDesk/1.0").timeout(8000).get();
                        var og = doc.selectFirst("meta[property=og:image]");
                        return new String[]{doc.title(), og == null ? "" : og.attr("content")};
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e.getMessage(), e);
                    }
                },
                info -> {
                    String title = info[0] == null || info[0].isBlank() ? url : info[0];
                    String image = info[1] == null || info[1].isBlank() ? ""
                            : "![" + title.replaceAll("[\\[\\]\"]", "'") + "](" + info[1] + ")\n\n";
                    MarkdownEditor.insertBlock(editor,
                            "\n" + image + "[" + title.replaceAll("[\\[\\]]", "'") + "](" + url + ")\n");
                    main.status("Đã chèn thẻ link.");
                },
                err -> main.status("Không lấy được thông tin liên kết: " + err.getMessage()));
    }

    /** Mindmap window over the note's heading outline (Canvas-MindMap visualization). */
    private void showMindmap() {
        if (current == null) {
            return;
        }
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.setTitle("Mindmap — " + current.title);
        var root = new com.lifedesk.ui.components.MindMapCanvas(editor.getText(),
                ctx.themeManager().previewPalette(ctx.config().theme).accent());
        javafx.scene.Scene scene = new javafx.scene.Scene(root, 900, 640);
        scene.getStylesheets().setAll(ctx.themeManager().stylesheetsFor(ctx.config().theme));
        stage.setScene(scene);
        stage.initOwner(main.stage());
        stage.show();
    }

    private javafx.scene.control.MenuButton headingMenu() {
        javafx.scene.control.MenuButton menu = new javafx.scene.control.MenuButton("H▾");
        menu.getStyleClass().addAll("ghost-btn", "md-toolbar-btn");
        for (int i = 4; i <= 6; i++) {
            final int level = i;
            javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem(
                    "Tiêu đề " + level + " (Ctrl+" + level + ")");
            item.setOnAction(e -> MarkdownEditor.prefixSelectedLines(editor, "#".repeat(level) + " "));
            menu.getItems().add(item);
        }
        menu.setTooltip(new Tooltip("Tiêu đề 4–6 (Ctrl+4..6)"));
        return menu;
    }

    private javafx.scene.Node sep() {
        javafx.scene.control.Separator s = new javafx.scene.control.Separator(
                javafx.geometry.Orientation.VERTICAL);
        s.setPrefHeight(18);
        return s;
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

    private void openNote(NoteMeta meta) {
        if (meta == current) {
            return; // selection re-fired by our own refresh; nothing to do
        }
        saveNow(); // flush previous note before switching
        unloadMediaBar();
        current = meta;
        if (meta == null) {
            showNotesEmptyState();
            return;
        }
        editorPane.setTop(headerStack);
        editorPane.setBottom(statusHint);
        layoutEditorSplit();
        String content = ctx.notes().readContent(meta.id);
        loadedHash = com.lifedesk.common.Fingerprint.sha256(content);
        editor.setText(content);
        outlinePane.update(meta.id, content);
        refreshMediaBar();
        titleField.setText(meta.title);
        tagsField.setText(String.join(", ", meta.tags));
        statusHint.setText("");
        renderPreview();
    }

    // ------------------------------------------------------------------ media notes

    /** Prompt for the media link; empty input clears it (media-notes schema: one field). */
    private void editMediaLink() {
        if (current == null) {
            return;
        }
        String existing = current.mediaLink == null ? "" : current.mediaLink;
        String url = Dialogs.prompt("Media note",
                "Đường dẫn audio/video (file local hoặc URL stream trực tiếp).\n"
                        + "Để trống rồi OK để gỡ. YouTube/mặc định: mở bằng trình duyệt:",
                existing).orElse(null);
        if (url == null) {
            return; // cancelled
        }
        ctx.notes().setMediaLink(current.id, url);
        current.mediaLink = url.trim();
        refreshMediaBar();
        layoutEditorSplit();
    }

    /** (Re)build the pinned player for the current note's media link. */
    private void refreshMediaBar() {
        unloadMediaBar();
        if (current == null || current.mediaLink == null || current.mediaLink.isBlank()) {
            layoutEditorSplit();
            return;
        }
        mediaBar = new com.lifedesk.ui.panes.MediaBar(
                ts -> MarkdownEditor.insertBlock(editor, ts),
                () -> {
                    ctx.notes().setMediaLink(current.id, "");
                    current.mediaLink = "";
                    unloadMediaBar();
                    layoutEditorSplit();
                });
        mediaBar.setOnTimestamp(ts -> MarkdownEditor.insertBlock(editor, ts));
        mediaBar.load(current.id, current.mediaLink);
        layoutEditorSplit();
    }

    private void unloadMediaBar() {
        if (mediaBar != null) {
            mediaBar.unload();
            mediaBar = null;
        }
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
            var tags = Tags.parse(tagsField.getText());
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
                statusHint.setText("● Đã lưu " + java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
                loadedHash = com.lifedesk.common.Fingerprint.sha256(editor.getText());
            }
        } catch (RuntimeException e) {
            statusHint.setText("LƯU THẤT BẠI: " + e.getMessage());
            Dialogs.error("Lưu ghi chú thất bại", e.getMessage()
                    + "\nNội dung vẫn còn trong trình soạn thảo — thử lại hoặc chép ra nơi khác.");
        }
        refreshNoteList();
    }

    
    private void createNote() {
        String title = Dialogs.prompt("Ghi chú mới", "Tiêu đề ghi chú:", "").orElse(null);
        if (title == null || title.isBlank()) {
            return;
        }
        NoteMeta meta = ctx.notes().create(title, currentFolder, "# " + title + "\n\n", new LinkedHashSet<>());
        refreshNoteList();
        selectNoteInTree(meta.id);
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
        com.lifedesk.ui.Dialogs.style(dialog); // theme + owner (manual initOwner skipped both before)
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
            refreshNoteList();
        }
    }

    @Override
    public void onShow() {
        refreshNoteList();
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
                    selectNoteInTree(meta.id);
                });
    }
}
