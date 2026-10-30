package com.lifedesk.ui.editors;

import com.lifedesk.app.AppContext;
import com.lifedesk.common.Tags;
import com.lifedesk.habits.Goal;
import com.lifedesk.notes.NoteMeta;
import com.lifedesk.tasks.ChecklistItem;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.RecurrenceRule;
import com.lifedesk.tasks.RecurrenceType;
import com.lifedesk.tasks.Task;
import com.lifedesk.tasks.TaskStatus;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.components.Chips;
import com.lifedesk.ui.components.FormGrid;
import com.lifedesk.ui.components.IconView;
import com.lifedesk.ui.components.WeekdayChecks;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Create/edit dialog for tasks: core fields, checklist editor, weekly
 * recurrence (shared {@link WeekdayChecks} row) and buffered note/goal links
 * (applied only on Save — a new task has no id to link against yet).
 */
public final class TaskDialog {
    private TaskDialog() {
    }

    /** Open the create/edit dialog; {@code onSaved} runs after it closes. */
    public static void open(AppContext ctx, MainWindow main, Task existing, Runnable onSaved) {
        Dialog<Task> dialog = Dialogs.newDialog();
        dialog.setTitle(existing == null ? "Công việc mới" : "Sửa công việc");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField title = new TextField(existing == null ? "" : existing.title);
        TextArea notes = new TextArea(existing == null ? "" : existing.notes);
        notes.setPrefRowCount(4);
        notes.setWrapText(true);
        ComboBox<Priority> priority = new ComboBox<>();
        priority.getItems().addAll(Priority.values());
        priority.setValue(existing == null ? Priority.MEDIUM : existing.priority);
        displayAs(priority, p -> Chips.priorityName(((Priority) p).name()));
        ComboBox<TaskStatus> status = new ComboBox<>();
        status.getItems().addAll(TaskStatus.values());
        status.setValue(existing == null ? TaskStatus.TODO : existing.status);
        displayAs(status, s -> Chips.taskStatusName(((TaskStatus) s).name()));
        DatePicker dueDate = new DatePicker(existing == null ? null : existing.dueDate);
        Spinner<Integer> hh = new Spinner<>(0, 23, existing != null && existing.dueTime != null ? existing.dueTime.getHour() : 9);
        Spinner<Integer> mm = new Spinner<>(0, 59, existing != null && existing.dueTime != null ? existing.dueTime.getMinute() : 0);
        hh.setPrefWidth(74);
        mm.setPrefWidth(74);
        CheckBox hasTime = new CheckBox("Có giờ");
        hasTime.setSelected(existing != null && existing.dueTime != null);
        hh.disableProperty().bind(hasTime.selectedProperty().not());
        mm.disableProperty().bind(hasTime.selectedProperty().not());
        TextField group = new TextField(existing == null ? "" : existing.group);
        TextField tags = new TextField(existing == null ? "" : String.join(", ", existing.tags));

        // Checklist editor
        VBox checklistBox = new VBox(4);
        List<CheckBox> checklistChecks = new ArrayList<>();
        List<TextField> checklistTexts = new ArrayList<>();
        Runnable rebuildChecklist = () -> {
            checklistBox.getChildren().clear();
            for (int i = 0; i < checklistChecks.size(); i++) {
                int idx = i;
                HBox row = new HBox(6, checklistChecks.get(idx), checklistTexts.get(idx));
                checklistBox.getChildren().add(row);
            }
        };
        if (existing != null) {
            for (ChecklistItem item : existing.checklist) {
                CheckBox cb = new CheckBox();
                cb.setSelected(item.done);
                TextField tf = new TextField(item.text);
                tf.setPrefWidth(320);
                checklistChecks.add(cb);
                checklistTexts.add(tf);
            }
        }
        rebuildChecklist.run();
        Button addCheck = new Button("Mục checklist", new IconView("plus", 15));
        addCheck.setOnAction(e -> {
            CheckBox cb = new CheckBox();
            TextField tf = new TextField();
            tf.setPrefWidth(320);
            checklistChecks.add(cb);
            checklistTexts.add(tf);
            rebuildChecklist.run();
        });

        // Recurrence
        ComboBox<RecurrenceType> recType = new ComboBox<>();
        recType.getItems().addAll(RecurrenceType.values());
        recType.setValue(existing == null || existing.recurrence == null ? RecurrenceType.NONE : existing.recurrence.type);
        displayAs(recType, r -> recurrenceName((RecurrenceType) r));
        Spinner<Integer> recInterval = new Spinner<>(1, 52, existing != null && existing.recurrence != null
                && existing.recurrence.interval > 0 ? existing.recurrence.interval : 1);
        recInterval.setPrefWidth(84);
        recInterval.disableProperty().bind(recType.valueProperty().isEqualTo(RecurrenceType.NONE));
        Label intervalLabel = new Label("Mỗi");
        recType.valueProperty().addListener((o, a, b) -> intervalLabel.setText(intervalUnitName(b)));
        intervalLabel.setText(intervalUnitName(recType.getValue()));
        List<CheckBox> dayChecks = new ArrayList<>();
        Set<DayOfWeek> selectedDays = existing == null || existing.recurrence == null
                ? Set.of()
                : existing.recurrence.daysOfWeek.stream().map(DayOfWeek::of).collect(Collectors.toSet());
        javafx.scene.layout.HBox daysBox = WeekdayChecks.row(selectedDays, dayChecks);
        daysBox.disableProperty().bind(recType.valueProperty().isNotEqualTo(RecurrenceType.WEEKLY));

        // Links: notes + goals (buffered until Save). The dialog covers the main
        // window's status bar, so the linked state must be visible IN the lists.
        ListView<NoteMeta> notePick = new ListView<>();
        notePick.getItems().addAll(ctx.notes().list());
        notePick.setPrefHeight(110);
        notePick.setPlaceholder(new Label("Chưa có ghi chú nào."));
        Set<String> linkedNotes = existing == null ? new LinkedHashSet<>() : new LinkedHashSet<>(existing.linkedNoteIds);
        notePick.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(NoteMeta item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : (linkedNotes.contains(item.id) ? "✓ " : "") + item.title);
            }
        });
        Button toggleNoteLink = new Button("Liên kết / bỏ liên kết");
        toggleNoteLink.setOnAction(e -> {
            NoteMeta n = notePick.getSelectionModel().getSelectedItem();
            if (n != null) {
                if (!linkedNotes.add(n.id)) {
                    linkedNotes.remove(n.id);
                }
                notePick.refresh();
                main.status("Liên kết ghi chú: " + linkedNotes.size() + " (xác nhận khi Lưu)");
            }
        });

        List<Goal> goals = ctx.habits().goals();
        ComboBox<String> goalPick = new ComboBox<>();
        goals.forEach(g -> goalPick.getItems().add(g.title()));
        goalPick.setPromptText("Chọn mục tiêu…");
        goalPick.setMaxWidth(Double.MAX_VALUE);
        Set<String> linkedGoals = existing == null ? new LinkedHashSet<>() : new LinkedHashSet<>(existing.linkedGoalIds);
        goalPick.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                String raw = empty || item == null ? null : item;
                if (raw == null) {
                    setText(null);
                    return;
                }
                boolean linked = goals.stream()
                        .filter(g -> g.title().equals(raw))
                        .findFirst().map(g -> linkedGoals.contains(g.id())).orElse(false);
                setText((linked ? "✓ " : "") + raw);
            }
        });
        goalPick.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });
        Button toggleGoalLink = new Button("Liên kết / bỏ liên kết");
        toggleGoalLink.setOnAction(e -> {
            String g = goalPick.getValue();
            if (g != null) {
                goals.stream().filter(x -> x.title().equals(g)).findFirst().ifPresent(goal -> {
                    if (!linkedGoals.add(goal.id())) {
                        linkedGoals.remove(goal.id());
                    }
                    // ComboBox has no refresh(); re-setting the same item repains
                    // the dropdown cells so the ✓ marker follows the state.
                    int idx = goalPick.getItems().indexOf(g);
                    if (idx >= 0) {
                        goalPick.getItems().set(idx, g);
                    }
                });
            }
        });

        // Priority/status share one row with equal columns; the due-date row keeps
        // the time editor inline with "Có giờ" so nothing floats or truncates.
        HBox metaRow = new HBox(12,
                labeled("Ưu tiên", priority), labeled("Trạng thái", status), labeled("Nhóm", group));
        metaRow.getChildren().forEach(n -> HBox.setHgrow(n, javafx.scene.layout.Priority.ALWAYS));
        priority.setPrefWidth(140);
        status.setPrefWidth(140);
        dueDate.setPrefWidth(150);
        HBox dueRow = new HBox(18,
                labeled("Hạn", dueDate),
                new VBox(2, new Label("Giờ"), new HBox(4, hh, new Label(":"), mm)),
                new VBox(2, new Label(" "), hasTime));

        HBox linksRow = new HBox(16,
                new VBox(2, new Label("Liên kết ghi chú"), notePick, toggleNoteLink),
                new VBox(2, new Label("Liên kết mục tiêu"), goalPick, toggleGoalLink));
        HBox.setHgrow(linksRow.getChildren().get(0), javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(linksRow.getChildren().get(1), javafx.scene.layout.Priority.ALWAYS);

        HBox repeatRow = new HBox(18, labeled("Lặp lại", recType),
                new VBox(2, intervalLabel, recInterval),
                new VBox(2, new Label("Vào các ngày (nếu lặp tuần)"), daysBox));

        // Minimal capture surface: a title and a deadline. Everything a task
        // rarely needs lives in a collapsed pane — scheduling one task should
        // not be a form-filling exercise. Tasks that carry detail data reopen
        // expanded so nothing is hidden from an editor.
        boolean hasDetailData = existing != null && (
                !(existing.notes == null || existing.notes.isBlank())
                || existing.priority != Priority.MEDIUM
                || existing.status != TaskStatus.TODO
                || !(existing.group == null || existing.group.isBlank())
                || !existing.tags.isEmpty()
                || !existing.checklist.isEmpty()
                || (existing.recurrence != null && existing.recurrence.type != RecurrenceType.NONE)
                || !existing.linkedNoteIds.isEmpty()
                || !existing.linkedGoalIds.isEmpty());
        TitledPane detail = new TitledPane("Chi tiết thêm (ghi chú, ưu tiên, lặp lại, liên kết…)",
                new VBox(10, labeled("Ghi chú", notes), metaRow,
                        labeled("Tags (phân cách bằng dấu phẩy)", tags),
                        new Separator(),
                        new Label("Checklist:"), checklistBox, addCheck,
                        new Separator(),
                        repeatRow,
                        new Separator(),
                        linksRow));
        detail.setExpanded(hasDetailData);

        VBox content = new VBox(10, labeled("Tiêu đề *", title), dueRow, detail);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.setOnShown(e -> title.requestFocus());

        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            try {
                Task t = existing == null ? new Task() : existing;
                t.title = title.getText();
                t.notes = notes.getText();
                t.priority = priority.getValue();
                t.status = status.getValue();
                t.dueDate = dueDate.getValue();
                t.dueTime = hasTime.isSelected() ? LocalTime.of(hh.getValue(), mm.getValue()) : null;
                t.group = group.getText().trim();
                t.tags = Tags.parse(tags.getText());
                t.checklist = new ArrayList<>();
                for (int i = 0; i < checklistChecks.size(); i++) {
                    String text = checklistTexts.get(i).getText();
                    if (!text.isBlank()) {
                        t.checklist.add(new ChecklistItem(text.trim(), checklistChecks.get(i).isSelected()));
                    }
                }
                RecurrenceRule rule = new RecurrenceRule();
                rule.type = recType.getValue();
                rule.interval = recInterval.getValue();
                rule.daysOfWeek = WeekdayChecks.selected(dayChecks).stream()
                        .map(DayOfWeek::getValue).sorted().collect(Collectors.toList());
                t.recurrence = rule;
                t.linkedNoteIds = new LinkedHashSet<>(linkedNotes);
                t.linkedGoalIds = new LinkedHashSet<>(linkedGoals);

                if (existing == null) {
                    // create() returns the new task; set the extra fields on it
                    // instead of re-scanning the list by title.
                    Task created = ctx.tasks().create(t.title, t.notes, t.priority, t.dueDate, t.dueTime,
                            t.group, new ArrayList<>(t.tags), t.recurrence);
                    created.checklist = t.checklist;
                    created.dueTime = t.dueTime;
                    created.linkedNoteIds = t.linkedNoteIds;
                    created.linkedGoalIds = t.linkedGoalIds;
                    ctx.tasks().update(created);
                    return created;
                }
                ctx.tasks().update(t);
                return t;
            } catch (RuntimeException ex) {
                Dialogs.error("Không lưu được", ex.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
        onSaved.run();
    }

    /** Dialog fields read better at a fixed width; structure shared via FormGrid. */
    private static VBox labeled(String labelText, javafx.scene.Node field) {
        if (field instanceof TextField tf) {
            tf.setPrefWidth(260);
        }
        return FormGrid.labeled(labelText, field);
    }

    /** Vietnamese display names for the recurrence combo (same list + button cell). */
    private static String recurrenceName(RecurrenceType t) {
        return switch (t) {
            case NONE -> "Không lặp";
            case DAILY -> "Hàng ngày";
            case WEEKLY -> "Hàng tuần";
            case MONTHLY -> "Hàng tháng";
        };
    }

    /** Interval label announces the unit so "Mỗi 3" reads as "Mỗi 3 tuần". */
    private static String intervalUnitName(RecurrenceType t) {
        return switch (t) {
            case NONE -> "Mỗi";
            case DAILY -> "Mỗi (ngày)";
            case WEEKLY -> "Mỗi (tuần)";
            case MONTHLY -> "Mỗi (tháng)";
        };
    }

    /** Render an enum combo with Vietnamese names in both the list and the button. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void displayAs(ComboBox<?> combo, java.util.function.Function<Object, String> name) {
        ComboBox raw = combo;
        raw.setCellFactory(lv -> new ListCell<Object>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : name.apply(item));
            }
        });
        raw.setButtonCell(new ListCell<Object>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : name.apply(item));
            }
        });
    }
}
