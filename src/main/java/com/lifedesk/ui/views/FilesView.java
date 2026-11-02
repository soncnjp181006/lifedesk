package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.files.FileHit;
import com.lifedesk.files.FileSearchOptions;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.RootGrants;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.IconView;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * File manager over user-granted roots. Read-only by design: no delete,
 * rename or move operations exist in this view or service. Opening a file
 * uses the OS default application. Minimal surface: pick a granted folder,
 * browse it, find files by name — nothing else.
 */
public class FilesView extends View {
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB"};

    private final ComboBox<Path> rootCombo = new ComboBox<>();
    private final TableView<FileHit> table = new TableView<>();
    private final TextField nameQuery = new TextField();
    private final Label scopeNote = new Label();
    /** The folder on screen — the page answers "where am I looking?" before anything else. */
    private final Label crumb = new Label();
    /** Appears only inside a subfolder, and names the granted folder it goes back to. */
    private final Button crumbUp = new Button();
    private javafx.concurrent.Task<List<FileHit>> searchTask;
    /** The "nothing added yet" placeholder — the one state with no folder to name. */
    private Node emptyPlaceholder;
    /** Shown only while a search is actually running. */
    private Button searchCancelBtn;
    /** The folder whose files are on screen; null when the table shows nothing. */
    private Path shownDir;

    public FilesView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "FILES";
    }

    @Override
    protected Node build() {
        // "Cấp quyền thư mục" is security vocabulary. The button adds a folder to look
        // in; the consent it implies is spelled out in the dialog that follows, where it
        // can be read before anything is stored.
        Button addRoot = new Button("Thêm thư mục", new IconView("plus", 15));
        addRoot.getStyleClass().add("primary-btn");
        addRoot.setOnAction(e -> addAllowedRoot());
        // The line that used to sit here restated the rail label. What a file page needs
        // at the top is where it is looking, so the spot now carries the folder on screen.
        HBox location = new HBox(8, crumb, crumbUp);
        location.setAlignment(Pos.CENTER_LEFT);
        crumb.getStyleClass().add("dim-label");
        crumb.setWrapText(true);
        crumb.setId("files-crumb");
        crumbUp.setVisible(false);
        crumbUp.setManaged(false);
        crumbUp.setOnAction(e -> browse(rootCombo.getValue()));
        HBox header = toolbar(location, addRoot);

        scopeNote.getStyleClass().add("dim-label");
        scopeNote.setWrapText(true);
        scopeNote.setText("LifeDesk chỉ liệt kê file trong những thư mục bạn thêm ở đây"
                + " — không xóa, không đổi tên, không di chuyển, và không quét cả ổ đĩa.");

        rootCombo.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
        rootCombo.setOnAction(e -> browse());

        nameQuery.setPromptText("Tên file chứa…");
        nameQuery.setPrefColumnCount(16);
        rootCombo.setPrefWidth(240);
        Button searchBtn = new Button("Tìm kiếm", new IconView("search", 15));
        searchBtn.getStyleClass().add("primary-btn");
        searchBtn.setOnAction(e -> runSearch());
        Button cancelSearch = new Button("Hủy tìm");
        cancelSearch.setOnAction(e -> {
            if (searchTask != null && searchTask.isRunning()) {
                searchTask.cancel(true);
            }
        });
        // A cancel button that cancels nothing is a button that teaches the user to
        // ignore buttons. It appears with the search it belongs to.
        cancelSearch.setVisible(false);
        cancelSearch.setManaged(false);
        searchCancelBtn = cancelSearch;

        HBox searchRow = new HBox(8, rootCombo, nameQuery, searchBtn, cancelSearch);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameQuery, Priority.ALWAYS);
        HBox.setHgrow(rootCombo, Priority.SOMETIMES);

        buildTable();
        emptyPlaceholder = EmptyState.withAction("folder",
                "Chưa có thư mục nào. Thêm một thư mục (Tài liệu, Downloads…) để xem và tìm"
                        + " file ở đó — trợ lý AI cũng tìm được file trong thư mục này.",
                "Thêm thư mục", this::addAllowedRoot);
        table.setPlaceholder(emptyPlaceholder);

        VBox.setVgrow(table, Priority.ALWAYS);
        refreshRoots();
        return page(header, scopeNote, searchRow, table);
    }

    private void buildTable() {
        TableColumn<FileHit, FileHit> nameCol = new TableColumn<>("Tên");
        nameCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        nameCol.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(FileHit item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(null);
                    Label name = new Label(item.name());
                    name.setGraphic(new IconView(item.directory() ? "folder" : "file-text", 14));
                    setGraphic(name);
                }
            }
        });
        TableColumn<FileHit, String> sizeCol = new TableColumn<>("Kích thước");
        sizeCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().directory() ? "<dir>" : humanSize(p.getValue().sizeBytes())));
        TableColumn<FileHit, String> modifiedCol = new TableColumn<>("Sửa lần cuối");
        modifiedCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                LocalDateTime.ofInstant(p.getValue().lastModified(), ZoneId.systemDefault()).format(DF)));
        TableColumn<FileHit, String> extCol = new TableColumn<>("Loại");
        extCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().directory() ? "Thư mục" : String.valueOf(p.getValue().extension())));
        TableColumn<FileHit, String> pathCol = new TableColumn<>("Đường dẫn");
        pathCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().path().toString()));
        table.getColumns().setAll(List.of(nameCol, sizeCol, modifiedCol, extCol, pathCol));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                FileHit hit = table.getSelectionModel().getSelectedItem();
                if (hit != null) {
                    if (hit.directory()) {
                        browse(hit.path());
                    } else {
                        openSelected();
                    }
                }
            }
        });
    }

    private static String humanSize(long bytes) {
        double b = bytes;
        int u = 0;
        while (b >= 1024 && u < SIZE_UNITS.length - 1) {
            b /= 1024;
            u++;
        }
        return String.format("%.1f %s", b, SIZE_UNITS[u]);
    }

    private void refreshRoots() {
        refreshRoots(shownDir != null ? shownDir : rootCombo.getValue());
    }

    /**
     * Re-read the granted folders and put back the one the user is looking at.
     *
     * <p>Falling back to the first entry is what made a grant look like a different
     * folder: the dialog confirmed "…\New folder", the selector then jumped to "…\User"
     * (the first root on the list) and the error that followed named a path nobody had
     * picked in that step.
     */
    private void refreshRoots(Path prefer) {
        rootCombo.getItems().setAll(ctx.files().allowedRoots());
        if (prefer != null && rootCombo.getItems().contains(prefer)) {
            rootCombo.getSelectionModel().select(prefer);
        } else if (!rootCombo.getItems().isEmpty()) {
            rootCombo.getSelectionModel().selectFirst();
        }
        // An empty root list used to leave a dropdown that silently opened nothing.
        boolean none = rootCombo.getItems().isEmpty();
        rootCombo.setDisable(none);
        rootCombo.setPromptText(none ? "Chưa thêm thư mục nào" : "Thư mục đã thêm");
        if (none) {
            table.setPlaceholder(emptyPlaceholder);
        }
    }

    private void addAllowedRoot() {
        Path granted = RootGrants.grantViaDialog(ctx, main,
                "Chọn thư mục để LifeDesk đọc được",
                "Cho phép đọc thư mục này?",
                "LifeDesk sẽ liệt kê được file trong:\n%s\n\n"
                        + "Trợ lý AI cũng tìm được file ở đó khi bạn hỏi. LifeDesk không xóa,"
                        + " không đổi tên và không di chuyển file.");
        if (granted != null) {
            showGranted(granted);
        }
    }

    /**
     * A folder has just been allowed: keep it and put it on screen.
     *
     * <p>One step, not two, because the halves used to disagree — the selector fell back to
     * the first folder on the list while the table was meant to show the chosen one, so
     * confirming "…\New folder" could browse (and then error about) "…\User".
     */
    public void showGranted(Path granted) {
        saveConfig();
        refreshRoots(granted);
        browse(granted);
    }

    private void browse() {
        Path root = rootCombo.getValue();
        if (root != null) {
            browse(root);
        }
    }

    private void browse(Path dir) {
        Path root = rootCombo.getValue();
        showLocation(dir, root);
        if (!ctx.files().isAllowed(dir)) {
            Dialogs.warn("Ngoài phạm vi", "Thư mục này nằm ngoài những thư mục bạn đã thêm.");
            return;
        }
        try {
            List<FileHit> rows = ctx.files().list(dir);
            table.getItems().setAll(rows);
            shownDir = dir;
            // The sentence under an empty list has to match why it is empty: a folder with
            // nothing in it is not a folder that cannot be read, and an error left over from
            // the previous folder would say the wrong one.
            table.setPlaceholder(rows.isEmpty()
                    ? new EmptyState("folder", dir + " chưa có file nào.")
                    : emptyPlaceholder);
        } catch (RuntimeException e) {
            // Never leave the previous folder's list on screen under a different name:
            // the failure takes its place, with the way out of it.
            table.getItems().clear();
            shownDir = null;
            showReadFailure(dir, e.getMessage());
        }
    }

    /**
     * What the table is showing, in one line, plus the way back.
     *
     * <p>Subfolders deliberately do not go into the picker: that list is the folders the
     * user allowed, and filling it with every folder visited turned it into history. The
     * picker keeps naming the granted folder, this line names the one on screen.
     */
    private void showLocation(Path dir, Path root) {
        if (dir == null) {
            crumb.setText("");
            crumbUp.setVisible(false);
            crumbUp.setManaged(false);
            return;
        }
        crumb.setText("Đang xem: " + dir);
        boolean insideSub = root != null && !root.equals(dir);
        if (root != null) {
            // A drive root has no last name element, so fall back to the path itself.
            Object name = root.getFileName();
            crumbUp.setText("Về " + (name != null ? name : root));
        }
        crumbUp.setVisible(insideSub);
        crumbUp.setManaged(insideSub);
    }

    /**
     * A grant that no longer reads — folder deleted, moved, or refused by Windows — used
     * to answer with a modal whose only button was "Đồng ý": the user acknowledged a
     * problem they could not act on, and the page behind it still showed some other
     * folder. The reason and the two ways out now sit in the list the failure replaced.
     */
    private void showReadFailure(Path dir, String reason) {
        String why = reason == null || reason.isBlank() ? "không rõ lý do" : reason;
        Button forget = new Button("Bỏ khỏi danh sách", new IconView("trash", 14));
        forget.setOnAction(e -> revoke(dir));
        Button retry = new Button("Thử lại");
        retry.setOnAction(e -> browse(dir));
        table.setPlaceholder(new EmptyState("triangle-alert",
                "Không đọc được " + dir + "\n" + why, forget, retry));
        main.status("Không đọc được " + dir.getFileName() + " — " + why);
    }

    /** Drop a dead grant, then show whatever still works. */
    private void revoke(Path dir) {
        ctx.config().allowedRoots.remove(dir.toString());
        ctx.files().setAllowedRoots(ctx.config().allowedRoots);
        saveConfig();
        table.getItems().clear();
        shownDir = null;
        showLocation(null, null);
        refreshRoots();
        if (!rootCombo.getItems().isEmpty()) {
            browse(rootCombo.getValue());
        }
        main.status("Đã bỏ " + dir.getFileName() + " khỏi danh sách thư mục");
    }

    private void runSearch() {
        Path root = rootCombo.getValue();
        if (root == null) {
            Dialogs.info("Chọn thư mục", "Thêm một thư mục trước đã, rồi tìm trong đó.");
            return;
        }
        FileSearchOptions options = new FileSearchOptions();
        options.root = root;
        options.nameQuery = nameQuery.getText();
        main.status("Đang tìm kiếm trong " + root + "…");
        searchRunning(true);

        searchTask = new javafx.concurrent.Task<>() {
            @Override
            protected List<FileHit> call() {
                return ctx.files().search(options);
            }
        };
        searchTask.setOnSucceeded(e -> {
            List<FileHit> hits = searchTask.getValue();
            table.getItems().setAll(hits);
            if (hits.isEmpty()) {
                table.setPlaceholder(new EmptyState("search",
                        "Không có file nào tên chứa \"" + options.nameQuery + "\" trong "
                                + root + "."));
            }
            searchRunning(false);
            main.status("Tìm kiếm xong: " + hits.size() + " kết quả (giới hạn " + options.maxResults + ")");
        });
        searchTask.setOnFailed(e -> {
            searchRunning(false);
            main.status("Tìm kiếm lỗi: " + searchTask.getException());
        });
        searchTask.setOnCancelled(e -> {
            searchRunning(false);
            main.status("Đã hủy tìm kiếm");
        });
        ctx.executors().io().execute(searchTask);
    }

    /** The cancel button belongs to a running search, and to nothing else. */
    private void searchRunning(boolean running) {
        if (searchCancelBtn != null) {
            searchCancelBtn.setVisible(running);
            searchCancelBtn.setManaged(running);
        }
    }

    private void openSelected() {
        FileHit hit = table.getSelectionModel().getSelectedItem();
        if (hit == null) {
            return;
        }
        try {
            boolean ok = ctx.files().openWithDefaultApp(hit.path());
            if (!ok) {
                Dialogs.warn("Không mở được", "Hệ điều hành không hỗ trợ mở file mặc định từ Java trên máy này.");
            }
        } catch (RuntimeException e) {
            Dialogs.error("Không mở được file", e.getMessage());
        }
    }

    @Override
    public void onShow() {
        refreshRoots();
    }

    @Override
    public void onHide() {
        if (searchTask != null && searchTask.isRunning()) {
            searchTask.cancel(true);
        }
    }
}
