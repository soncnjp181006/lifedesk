package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.files.FileHit;
import com.lifedesk.files.FileManagerService;
import com.lifedesk.files.FileSearchOptions;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.IconView;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * File manager over user-granted roots. Read-only by design: no delete,
 * rename or move operations exist in this view or service. Opening a file
 * uses the OS default application.
 */
public class FilesView extends View {
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB"};

    private final ComboBox<Path> rootCombo = new ComboBox<>();
    private final TableView<FileHit> table = new TableView<>();
    private final TextField nameQuery = new TextField();
    private final TextField extQuery = new TextField();
    private final TextField minSize = new TextField();
    private final CheckBox recursive = new CheckBox("Quét đệ quy (tối đa 6 cấp)");
    private final ListView<String> favorites = new ListView<>();
    private final Label scopeNote = new Label();
    private javafx.concurrent.Task<List<FileHit>> searchTask;

    public FilesView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "FILES";
    }

    @Override
    protected Node build() {
        Label title = new Label("Tệp (chỉ đọc)");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button addRoot = new Button("Cấp quyền thư mục", new IconView("plus", 15));
        addRoot.setOnAction(e -> addAllowedRoot());
        Button auditBtn = new Button("Kiểm tra dữ liệu", new IconView("flask-conical", 15));
        auditBtn.setTooltip(new Tooltip("Quét trùng lặp / file quá lớn / file rỗng (chỉ đọc)"));
        auditBtn.setOnAction(e -> runAudit());
        HBox header = new HBox(10, title, spacer, auditBtn, addRoot);
        header.getStyleClass().add("view-header");
        header.setAlignment(Pos.CENTER_LEFT);

        scopeNote.getStyleClass().add("dim-label");
        scopeNote.setWrapText(true);
        scopeNote.setText("LifeDesk CHỈ đọc các thư mục bạn cấp quyền. Không xóa/đổi tên/di chuyển file; "
                + "không quét toàn bộ ổ đĩa; không thu thập nội dung file.");

        rootCombo.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
        rootCombo.setOnAction(e -> browse());

        nameQuery.setPromptText("Tên file chứa…");
        extQuery.setPromptText("Phần mở rộng (vd: pdf, docx)");
        minSize.setPromptText("Dung lượng tối thiểu (byte)");
        nameQuery.setPrefColumnCount(16);
        extQuery.setPrefColumnCount(16);
        minSize.setPrefColumnCount(14);
        rootCombo.setPrefWidth(240);
        rootCombo.setPromptText("Thư mục đã cấp quyền");
        recursive.setSelected(true);
        Button searchBtn = new Button("Tìm kiếm", new IconView("search", 15));
        searchBtn.getStyleClass().add("primary-btn");
        searchBtn.setOnAction(e -> runSearch());
        Button cancelSearch = new Button("Hủy tìm");
        cancelSearch.setOnAction(e -> {
            if (searchTask != null && searchTask.isRunning()) {
                searchTask.cancel(true);
            }
        });
        Button favBtn = new Button("Thêm vào yêu thích", new IconView("star", 15));
        favBtn.setOnAction(e -> addFavorite());
        Button favRemove = new Button("Bỏ yêu thích");
        favRemove.setOnAction(e -> removeFavorite());

        // Two rows: a single row clipped every filter's placeholder at 1080px.
        // Selecting a root in the combo already browses it; a separate "Duyệt" button
        // was a second path to the same call and did nothing while no root existed.
        HBox searchRow = new HBox(8, rootCombo, nameQuery, searchBtn, cancelSearch);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox filterRow = new HBox(8, extQuery, minSize, recursive);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameQuery, Priority.ALWAYS);
        HBox.setHgrow(rootCombo, Priority.SOMETIMES);

        buildTable();
        table.setPlaceholder(new EmptyState("folder", "Chọn một thư mục đã cấp quyền để duyệt hoặc tìm kiếm."));

        favorites.setPrefWidth(240);
        favorites.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });
        favorites.getItems().addAll(ctx.files().favorites().stream().map(f -> f.path).toList());
        favorites.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                Path p = Paths.get(b);
                if (ctx.files().isAllowed(p)) {
                    rootCombo.getItems().stream().filter(r -> r.equals(p)).findFirst()
                            .ifPresentOrElse(rootCombo.getSelectionModel()::select,
                                    () -> openRecordList(p));
                } else {
                    main.status("Thư mục yêu thích chưa được cấp quyền trong phiên này");
                }
            }
        });
        VBox favActions = new VBox(6, favBtn, favRemove);
        favBtn.setMaxWidth(Double.MAX_VALUE);
        favRemove.setMaxWidth(Double.MAX_VALUE);
        VBox favBox = new VBox(8, new Label("Thư mục yêu thích"), favorites, favActions);
        favBox.setPrefWidth(260);

        VBox mainPane = new VBox(10, searchRow, filterRow, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        HBox content = new HBox(12, mainPane, favBox);
        HBox.setHgrow(mainPane, Priority.ALWAYS);

        VBox root = new VBox(10, header, scopeNote, content);
        VBox.setVgrow(root, Priority.ALWAYS);
        refreshRoots();
        return root;
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
        Path selected = rootCombo.getValue();
        rootCombo.getItems().setAll(ctx.files().allowedRoots());
        if (selected != null && rootCombo.getItems().contains(selected)) {
            rootCombo.getSelectionModel().select(selected);
        } else if (!rootCombo.getItems().isEmpty()) {
            rootCombo.getSelectionModel().selectFirst();
        }
        // An empty root list used to leave a dropdown that silently opened nothing.
        boolean none = rootCombo.getItems().isEmpty();
        rootCombo.setDisable(none);
        rootCombo.setPromptText(none ? "Chưa cấp quyền thư mục nào" : "Thư mục đã cấp quyền");
    }

    /** Data-health audit (vault-plus adapted): duplicates / oversize / empty, read-only. */
    private void runAudit() {
        if (ctx.files().allowedRoots().isEmpty()) {
            Dialogs.info("Chưa có thư mục", "Cấp quyền ít nhất một thư mục trước khi quét.");
            return;
        }
        javafx.scene.control.Dialog<Void> dialog = Dialogs.newDialog();
        dialog.setTitle("Kiểm tra dữ liệu");
        dialog.setHeaderText("Kết quả quét (chỉ đọc — app không tự xóa gì)");
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        VBox list = new VBox(4);
        javafx.scene.control.ProgressIndicator progress = new javafx.scene.control.ProgressIndicator();
        HBox loading = new HBox(10, new Label("Đang quét…"), progress);
        dialog.getDialogPane().setContent(new VBox(8, loading));
        dialog.setResizable(true);
        com.lifedesk.ui.Fx.async(ctx.executors().io(),
                () -> ctx.files().audit(100, 20000),
                findings -> javafx.application.Platform.runLater(() -> {
                    loading.setVisible(false);
                    loading.setManaged(false);
                    if (findings.isEmpty()) {
                        list.getChildren().add(new Label("Không phát hiện vấn đề nào. ✔"));
                    }
                    java.util.Map<String, String> kindLabel = java.util.Map.of(
                            "duplicate", "Trùng lặp", "oversize", "Quá lớn", "empty", "Rỗng");
                    for (var f : findings) {
                        Label row = new Label("[" + kindLabel.getOrDefault(f.kind(), f.kind()) + "] "
                                + f.path().getFileName() + " — " + f.detail());
                        row.setWrapText(true);
                        row.getStyleClass().add("dim-label");
                        list.getChildren().add(row);
                    }
                    if (findings.size() >= 200) {
                        list.getChildren().add(new Label("(dừng ở giới hạn 20000 file — kết quả có thể chưa đầy đủ)"));
                    }
                }),
                err -> javafx.application.Platform.runLater(() -> {
                    loading.setVisible(false);
                    list.getChildren().add(new Label("Lỗi quét: " + err.getMessage()));
                }));
        dialog.getDialogPane().setContent(new VBox(8, loading, list));
        dialog.showAndWait();
    }

    private void addAllowedRoot() {
        java.nio.file.Path granted = com.lifedesk.ui.RootGrants.grantViaDialog(ctx, main,
                "Cấp quyền truy cập thư mục cho LifeDesk",
                "Cho phép LifeDesk đọc file trong:\n" + "%s"
                        + "\n(Đồng nghĩa với cho phép agent tìm kiếm file trong thư mục này)");
        if (granted == null) {
            return;
        }
        saveConfig();
        refreshRoots();
        browse();
    }

    private void browse() {
        Path root = rootCombo.getValue();
        if (root != null) {
            browse(root);
        }
    }

    private void browse(Path dir) {
        if (!ctx.files().isAllowed(dir)) {
            Dialogs.warn("Ngoài phạm vi", "Thư mục này chưa được cấp quyền truy cập.");
            return;
        }
        try {
            table.getItems().setAll(ctx.files().list(dir));
            if (!rootCombo.getItems().contains(dir)) {
                rootCombo.getItems().add(dir);
                rootCombo.getSelectionModel().select(dir);
            }
        } catch (RuntimeException e) {
            Dialogs.error("Không đọc được thư mục", e.getMessage());
        }
    }

    private void openRecordList(Path dir) {
        browse(dir);
    }

    private void runSearch() {
        Path root = rootCombo.getValue();
        if (root == null) {
            Dialogs.info("Chọn thư mục", "Hãy chọn một thư mục đã cấp quyền.");
            return;
        }
        FileSearchOptions options = new FileSearchOptions();
        options.root = root;
        options.nameQuery = nameQuery.getText();
        if (!extQuery.getText().isBlank()) {
            options.extensions = java.util.Set.of(extQuery.getText().trim().toLowerCase().split("[,\\s]+"));
        }
        if (!minSize.getText().isBlank()) {
            try {
                options.minSizeBytes = Long.parseLong(minSize.getText().trim());
            } catch (NumberFormatException e) {
                Dialogs.error("Dung lượng không hợp lệ", "Nhập số byte, ví dụ 1000000");
                return;
            }
        }
        options.includeSubfolders = recursive.isSelected();
        main.status("Đang tìm kiếm trong " + root + "…");

        searchTask = new javafx.concurrent.Task<>() {
            @Override
            protected List<FileHit> call() {
                return ctx.files().search(options);
            }
        };
        searchTask.setOnSucceeded(e -> {
            List<FileHit> hits = searchTask.getValue();
            table.getItems().setAll(hits);
            main.status("Tìm kiếm xong: " + hits.size() + " kết quả (giới hạn " + options.maxResults + ")");
        });
        searchTask.setOnFailed(e -> main.status("Tìm kiếm lỗi: " + searchTask.getException()));
        searchTask.setOnCancelled(e -> main.status("Đã hủy tìm kiếm"));
        ctx.executors().io().execute(searchTask);
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

    private void addFavorite() {
        Path root = rootCombo.getValue();
        if (root == null) {
            return;
        }
        ctx.files().addFavorite(root.toString(), root.getFileName().toString());
        favorites.getItems().setAll(ctx.files().favorites().stream().map(f -> f.path).toList());
    }

    private void removeFavorite() {
        String selected = favorites.getSelectionModel().getSelectedItem();
        if (selected != null) {
            ctx.files().removeFavorite(selected);
            favorites.getItems().setAll(ctx.files().favorites().stream().map(f -> f.path).toList());
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
