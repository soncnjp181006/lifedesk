package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.Task;
import com.lifedesk.tasks.TaskQuickAdd;
import com.lifedesk.tasks.TaskStatus;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.Combos;
import com.lifedesk.ui.components.Chips;
import com.lifedesk.ui.components.EmptyState;
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
import javafx.scene.layout.HBox;

import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /** Live count line on the action row: rewrites itself on every refresh. */
    private final Label subtitle = new Label();

    public TasksView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "TASKS";
    }

    @Override
    protected Node build() {
        searchField.setPromptText("Tìm công việc…");
        Fx.debounced(searchField, this::refresh);
        groupFilter.getItems().add("(tất cả)");
        groupFilter.setValue("(tất cả)");
        statusFilter.getItems().addAll("(tất cả)", "TODO", "IN_PROGRESS", "DONE", "CANCELLED");
        statusFilter.setValue("(tất cả)");
        priorityFilter.getItems().addAll("(tất cả)", "URGENT", "HIGH", "MEDIUM", "LOW");
        priorityFilter.setValue("(tất cả)");
        // Filters keep the raw enum values as keys but display Vietnamese, like the
        // task chips and the editor dialog — raw "URGENT"/"TODO" read as a bug.
        // "(tất cả)" is this filter's own sentinel, so the pass-through lives here.
        Combos.label(statusFilter, v -> v.startsWith("(") ? v : Chips.taskStatusName(v));
        Combos.label(priorityFilter, v -> v.startsWith("(") ? v : Chips.priorityName(v));
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

        // Quick add: Todoist-style single line — most captures are "a title,
        // maybe a date". The full dialog (Ctrl+N) stays for the rest.
        TextField quickAdd = new TextField();
        quickAdd.setPromptText("Thêm việc nhanh: \"nộp báo cáo @mai 9h #work !cao\" — Enter để thêm");
        quickAdd.setOnAction(e -> quickAddTask(quickAdd));

        // Same segmented look as the note editor's mode switch, and the same reason:
        // loose toggle buttons were squeezed to "Li…" / "Bo…" on a narrow window.
        // It lives in the header now, so the filter strip keeps its own width.
        HBox viewModeRow = new HBox();
        viewModeRow.getStyleClass().add("mode-row");
        viewModeRow.setMaxHeight(Region.USE_PREF_SIZE);
        viewModeRow.getChildren().addAll(listToggle, boardToggle);

        // Two rows instead of one crowded line: the page header answers "what is
        // this screen", the filter strip answers "which slice am I looking at".
        HBox headerRow = toolbar(subtitle, deleteSelectedBtn, newBtn);

        searchField.setMinWidth(240);
        // FlowPane, not HBox: with every filter pinned to its real label width the
        // strip could not shrink below 825px and pushed the page sideways at the
        // minimum window size. Wrapping keeps every label readable and costs one
        // extra row only on a narrow window.
        javafx.scene.layout.FlowPane filterRow = new javafx.scene.layout.FlowPane(8, 8);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.getStyleClass().add("toolbar-strip");
        filterRow.getChildren().addAll(searchField,
                new Label("Nhóm:"), groupFilter, new Label("Trạng thái:"), statusFilter,
                new Label("Ưu tiên:"), priorityFilter, viewModeRow);
        searchField.setPrefWidth(520);

        buildTable();
        table.setPlaceholder(EmptyState.withAction("clipboard-list",
                "Chưa có công việc nào.", "Thêm việc đầu tiên", () -> openEditor(null)));

        buildBoard();

        HBox.setHgrow(listPane, javafx.scene.layout.Priority.ALWAYS);
        VBox root = page(headerRow, quickAdd, filterRow, listPane);
        VBox.setVgrow(listPane, javafx.scene.layout.Priority.ALWAYS);

        watch(ctx.tasks().changes());
        return root;
    }

    /** Enter in the quick-add bar: parse, create with defaults, clear the line. */
    private void quickAddTask(TextField quickAdd) {
        TaskQuickAdd.Parsed p = TaskQuickAdd.parse(quickAdd.getText());
        if (p == null) {
            return;
        }
        try {
            ctx.tasks().create(p.title(), "", p.priority(), p.dueDate(), p.dueTime(),
                    p.group(), p.tags(), null);
        } catch (RuntimeException ex) {
            Dialogs.error("Không thêm được việc", ex.getMessage());
            return;
        }
        quickAdd.clear();
        // No refresh(): the notifier the service fires already queues one repaint.
        main.status("Đã thêm: " + p.title());
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
                    // The title is the flexible part of this cell; the chip is not.
                    // With both left at their computed minimum, a narrow window
                    // squeezed the chip too and the row read "quá h…". It may shorten,
                    // but the whole title has to stay readable — a task list where the
                    // only way to finish reading a line is to open the editor is a list
                    // you cannot skim.
                    Label l = com.lifedesk.ui.Fx.shrinkable(new Label(item.title));
                    if (item.status == TaskStatus.DONE) {
                        l.getStyleClass().add("task-done");
                    }
                    HBox box = new HBox(6, l);
                    if (item.isOverdue(LocalDate.now())) {
                        Label chip = Chips.overdue("quá hạn");
                        chip.setMinWidth(Region.USE_PREF_SIZE);
                        box.getChildren().add(chip);
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
        // JavaFX leaves a column's floor at its preferred width, so the table asked
        // for ~810px even though every column here holds a short chip or a date.
        for (TableColumn<Task, ?> c : table.getColumns()) {
            // Measured, not guessed: the narrow-window probe reports the width each
            // cell label needs ("Hoàn thành" 76px, "Trung bình" 73px). A column
            // narrower than its content does not wrap, it writes "Hoàn …" — a
            // status the user has to guess at.
            c.setMinWidth(c == titleCol ? 180 : c == statusCol ? 96 : c == prioCol ? 92
                    : c == groupCol ? 88 : 64);
        }
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

    /** Highlighted card on the board; null when nothing is picked. */
    private VBox selectedCardNode;

    private void refreshBoard(List<Task> tasks) {
        boardBox.getChildren().clear();
        selectedCardNode = null;
        Map<Task, VBox> cardNodes = new HashMap<>();
        for (TaskStatus status : List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS, TaskStatus.DONE)) {
            Label header = new Label(switch (status) {
                case TODO -> "Cần làm";
                case IN_PROGRESS -> "Đang làm";
                case DONE -> "Hoàn thành";
                default -> status.name();
            });
            header.getStyleClass().add("section-label");
            VBox column = new VBox(header);
            column.getStyleClass().add("board-column");
            column.setPrefWidth(300);
            tasks.stream().filter(t -> t.status == status).limit(30).forEach(t -> {
                // A board column is 300px wide, so a long title lost its tail with no
                // way to read it. The card keeps one line and the whole title rides on
                // the hover.
                Label cardTitle = com.lifedesk.ui.Fx.shrinkable(new Label(t.title));
                VBox cardNode = new VBox(4, cardTitle,
                        new HBox(4, Chips.priority(t.priority.name()),
                                new Label(t.dueDate == null ? "" : t.dueDate.format(DF))));
                cardNode.getStyleClass().add("board-card");
                // Click = select (visible highlight — the user must see WHAT they
                // are about to edit/delete); double-click = edit.
                cardNode.setOnMouseClicked(e -> {
                    if (e.getClickCount() == 2) {
                        openEditor(t);
                        return;
                    }
                    highlightBoardCard(cardNodes, cardNode, t);
                });
                cardNode.setCursor(javafx.scene.Cursor.HAND);
                column.getChildren().add(cardNode);
                cardNodes.put(t, cardNode);
            });
            boardBox.getChildren().add(column);
            HBox.setHgrow(column, javafx.scene.layout.Priority.SOMETIMES);
        }
        // A refresh (filter/search) must not silently drop the visible selection.
        if (selected != null) {
            VBox keep = cardNodes.get(selected);
            if (keep != null) {
                highlightBoardCard(cardNodes, keep, selected);
            }
        }
    }

    private void highlightBoardCard(Map<Task, VBox> cardNodes, VBox node, Task t) {
        if (selectedCardNode != null) {
            selectedCardNode.getStyleClass().remove("board-card-selected");
        }
        selectedCardNode = node;
        node.getStyleClass().add("board-card-selected");
        selected = t;
        if (deleteSelectedBtn != null) {
            deleteSelectedBtn.setDisable(false);
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
            // No refresh(): the notifier the service fires already queues one repaint.
        }
    }

    @Override
    protected void refresh() {
        List<Task> tasks = filtered();
        long open = ctx.tasks().all().stream()
                .filter(t -> t.status != com.lifedesk.tasks.TaskStatus.DONE
                        && t.status != com.lifedesk.tasks.TaskStatus.CANCELLED)
                .count();
        int late = ctx.tasks().overdue().size();
        subtitle.setText(open + " việc đang mở" + (late > 0 ? "  ·  " + late + " quá hạn" : "  ·  không có việc trễ hạn"));
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
        // Only re-read when the module actually changed since the last paint.
        refreshWhenChanged(ctx.tasks().changes());
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
