package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.notes.NoteMeta;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.Task;
import com.lifedesk.tasks.TaskStatus;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.common.Tags;
import com.lifedesk.ui.components.Chips;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.FormGrid;
import com.lifedesk.ui.editors.TaskDialog;
import com.lifedesk.ui.components.IconView;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Tasks module: list + board views, full editor with checklist/recurrence/links. */
public class TasksView extends View {
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM");

    private final TableView<Task> table = new TableView<>();
    private final HBox boardBox = new HBox(10);
    private boolean boardMode = false;
    private final BorderPane listPane = new BorderPane();
    private final TextField searchField = new TextField();
    private final ComboBox<String> groupFilter = new ComboBox<>();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final ComboBox<String> priorityFilter = new ComboBox<>();
    private Button deleteSelectedBtn;
    private Task selected;

    public TasksView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "TASKS";
    }

    @Override
    protected Node build() {
        Label title = new Label("Công việc");
        title.getStyleClass().add("title-label");

        searchField.setPromptText("Tìm công việc…");
        searchField.textProperty().addListener((o, a, b) -> refresh());
        groupFilter.getItems().add("(tất cả)");
        groupFilter.setValue("(tất cả)");
        statusFilter.getItems().addAll("(tất cả)", "TODO", "IN_PROGRESS", "DONE", "CANCELLED");
        statusFilter.setValue("(tất cả)");
        priorityFilter.getItems().addAll("(tất cả)", "URGENT", "HIGH", "MEDIUM", "LOW");
        priorityFilter.setValue("(tất cả)");
        groupFilter.setOnAction(e -> refresh());
        statusFilter.setOnAction(e -> refresh());
        priorityFilter.setOnAction(e -> refresh());

        ToggleButton boardToggle = new ToggleButton("Board", new IconView("kanban", 15));
        ToggleButton listToggle = new ToggleButton("List", new IconView("list", 15));
        listToggle.setSelected(true);
        boardToggle.setOnAction(e -> {
            boardMode = true;
            listToggle.setSelected(false);
            boardToggle.setSelected(true);
            refresh();
        });
        listToggle.setOnAction(e -> {
            boardMode = false;
            boardToggle.setSelected(false);
            listToggle.setSelected(true);
            refresh();
        });

        Button newBtn = new Button("Công việc mới", new IconView("plus", 15));
        addAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), newBtn::fire);
        newBtn.getStyleClass().add("primary-btn");
        newBtn.setOnAction(e -> openEditor(null));

        deleteSelectedBtn = new Button("Xóa", new IconView("trash", 15));
        deleteSelectedBtn.setOnAction(e -> deleteSelected());
        deleteSelectedBtn.setDisable(true);

        HBox toolbar = new HBox(8, title, new Region(), searchField,
                new Label("Nhóm:"), groupFilter, new Label("Trạng thái:"), statusFilter,
                new Label("Ưu tiên:"), priorityFilter, listToggle, boardToggle, deleteSelectedBtn, newBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, javafx.scene.layout.Priority.ALWAYS);
        Region r = (Region) toolbar.getChildren().get(1);
        HBox.setHgrow(r, javafx.scene.layout.Priority.NEVER);

        buildTable();
        table.setPlaceholder(new EmptyState("clipboard-list", "Chưa có công việc nào."));

        buildBoard();

        HBox.setHgrow(listPane, javafx.scene.layout.Priority.ALWAYS);
        VBox root = new VBox(10, toolbar, listPane);
        VBox.setVgrow(listPane, javafx.scene.layout.Priority.ALWAYS);

        ctx.tasks().changes().addListener(this::scheduleRefresh);
        return root;
    }

    private void buildTable() {
        TableColumn<Task, Task> titleCol = new TableColumn<>("Tiêu đề");
        titleCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        titleCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Task item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label l = new Label(item.title);
                    if (item.status == TaskStatus.DONE) {
                        l.setStyle("-fx-opacity: 0.55;");
                    }
                    HBox box = new HBox(6, l);
                    if (item.isOverdue(LocalDate.now())) {
                        box.getChildren().add(Chips.overdue("quá hạn"));
                    }
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                    setText(null);
                }
            }
        });
        titleCol.setPrefWidth(340);

        TableColumn<Task, Task> statusCol = new TableColumn<>("Trạng thái");
        statusCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Task item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : Chips.taskStatus(item.status.name()));
            }
        });

        TableColumn<Task, Task> prioCol = new TableColumn<>("Ưu tiên");
        prioCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        prioCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Task item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null : Chips.priority(item.priority.name()));
            }
        });

        TableColumn<Task, String> dueCol = new TableColumn<>("Hạn");
        dueCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().dueDate == null ? "—" : p.getValue().dueDate.format(DF)
                        + (p.getValue().dueTime != null ? " " + p.getValue().dueTime : "")));

        TableColumn<Task, String> groupCol = new TableColumn<>("Nhóm");
        groupCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().group));

        TableColumn<Task, Integer> progressCol = new TableColumn<>("Tiến độ");
        progressCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().checklistProgress()));
        progressCol.setCellFactory(col -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();

            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    bar.setProgress(item / 100.0);
                    setGraphic(bar);
                }
            }
        });

        TableColumn<Task, Task> actionsCol = new TableColumn<>("Thao tác");
        actionsCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        actionsCol.setCellFactory(col -> new TableCell<>() {
            private final Button done = new Button("", new IconView("check", 14));
            private final Button edit = new Button("", new IconView("pencil", 14));
            private final Button del = new Button("", new IconView("trash", 14));

            {
                done.getStyleClass().add("icon-btn");
                edit.getStyleClass().add("icon-btn");
                del.getStyleClass().add("icon-btn");
                done.setTooltip(new Tooltip("Hoàn thành"));
                edit.setTooltip(new Tooltip("Sửa công việc"));
                del.setTooltip(new Tooltip("Xóa công việc"));
                done.setOnAction(e -> {
                    Task t = getTableRow() == null ? null : getTableRow().getItem();
                    if (t != null) {
                        ctx.tasks().setStatus(t.id, TaskStatus.DONE);
                    }
                });
                edit.setOnAction(e -> {
                    Task t = getTableRow() == null ? null : getTableRow().getItem();
                    if (t != null) {
                        openEditor(t);
                    }
                });
                del.setOnAction(e -> {
                    Task t = getTableRow() == null ? null : getTableRow().getItem();
                    if (t != null && Dialogs.confirm("Xóa công việc", "Xóa \"" + t.title + "\"?")) {
                        ctx.tasks().delete(t.id);
                    }
                });
                HBox box = new HBox(4, done, edit, del);
                box.setAlignment(Pos.CENTER);
                setGraphic(box);
                setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
            }

            @Override
            protected void updateItem(Task item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(empty);
                setVisible(!empty);
            }
        });

        table.getColumns().setAll(List.of(titleCol, statusCol, prioCol, dueCol, groupCol, progressCol, actionsCol));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            selected = b;
            if (deleteSelectedBtn != null) {
                deleteSelectedBtn.setDisable(b == null);
            }
        });
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                openEditor(table.getSelectionModel().getSelectedItem());
            }
        });
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        listPane.setCenter(table);
    }

    private void buildBoard() {
        boardBox.setPadding(new Insets(4));
        VBox.setVgrow(boardBox, javafx.scene.layout.Priority.ALWAYS);
    }

    private void refreshBoard(List<Task> tasks) {
        boardBox.getChildren().clear();
        for (TaskStatus status : List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.DONE)) {
            Label header = new Label(switch (status) {
                case TODO -> "Cần làm";
                case IN_PROGRESS -> "Đang làm";
                case DONE -> "Hoàn thành";
                default -> status.name();
            });
            header.getStyleClass().add("section-label");
            VBox column = new VBox(8, header);
            column.setStyle("-fx-background-color: -ld-surface; -fx-background-radius: 10; -fx-padding: 10;");
            column.setPrefWidth(300);
            tasks.stream().filter(t -> t.status == status).limit(30).forEach(t -> {
                VBox cardNode = new VBox(4, new Label(t.title),
                        new HBox(4, Chips.priority(t.priority.name()),
                                new Label(t.dueDate == null ? "" : t.dueDate.format(DF))));
                cardNode.getStyleClass().add("card");
                cardNode.setStyle("-fx-padding: 8; -fx-background-color: -ld-card;");
                cardNode.setOnMouseClicked(e -> {
                    selected = t;
                    if (deleteSelectedBtn != null) {
                        deleteSelectedBtn.setDisable(false);
                    }
                    openEditor(t);
                });
                cardNode.setCursor(javafx.scene.Cursor.HAND);
                column.getChildren().add(cardNode);
            });
            boardBox.getChildren().add(column);
            HBox.setHgrow(column, javafx.scene.layout.Priority.SOMETIMES);
        }
    }

    private List<Task> filtered() {
        String q = searchField.getText();
        String g = groupFilter.getValue();
        String s = statusFilter.getValue();
        String p = priorityFilter.getValue();
        return ctx.tasks().search(q, t -> {
            if (g != null && !"(tất cả)".equals(g) && !g.equals(t.group)) {
                return false;
            }
            if (s != null && !"(tất cả)".equals(s) && !s.equals(t.status.name())) {
                return false;
            }
            return p == null || "(tất cả)".equals(p) || p.equals(t.priority.name());
        }, Comparator.comparing(Task::getDueDateOrMax).thenComparing(t -> -t.priority.ordinal()));
    }

    private void deleteSelected() {
        if (selected == null) {
            return;
        }
        if (Dialogs.confirm("Xóa công việc", "Xóa công việc \"" + selected.title + "\"?")) {
            ctx.tasks().delete(selected.id);
            selected = null;
            if (deleteSelectedBtn != null) {
                deleteSelectedBtn.setDisable(true);
            }
            table.getSelectionModel().clearSelection();
            refresh();
        }
    }

    @Override
    protected void refresh() {
        List<Task> tasks = filtered();
        if (boardMode) {
            table.setVisible(false);
            listPane.setCenter(boardBox);
            refreshBoard(tasks);
        } else {
            listPane.setCenter(table);
            table.getItems().setAll(tasks);
        }
        var groups = ctx.tasks().groups();
        String current = groupFilter.getValue();
        groupFilter.getItems().setAll("(tất cả)");
        groupFilter.getItems().addAll(groups);
        groupFilter.setValue(groups.contains(current) ? current : "(tất cả)");
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void openRecord(String recordId) {
        ctx.tasks().all().stream().filter(t -> t.id.equals(recordId)).findFirst()
                .ifPresent(this::openEditor);
    }

    // ------------------------------------------------------------------ editor

    void openEditor(Task existing) {
        TaskDialog.open(ctx, main, existing, this::refresh);
    }
}
