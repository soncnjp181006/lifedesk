package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.habits.ChangeLogEntry;
import com.lifedesk.habits.Goal;
import com.lifedesk.habits.GoalStatus;
import com.lifedesk.habits.Habit;
import com.lifedesk.habits.HabitService;
import com.lifedesk.habits.ProgressMode;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.components.Card;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.Combos;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.FormGrid;
import com.lifedesk.ui.components.IconView;
import com.lifedesk.ui.components.WeekdayChecks;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Habits & Goals: completion tracking, streaks, weekly review, history adjustments. */
public class HabitsView extends View {
    private final VBox habitCards = new VBox(10);
    private final TableView<Goal> goalTable = new TableView<>();
    private final TableView<ChangeLogEntry> logTable = new TableView<>();
    private ToggleButton todayOnlyMode;
    private javafx.scene.control.TabPane tabPaneRef;

    public HabitsView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "HABITS";
    }

    @Override
    protected Node build() {
        todayOnlyMode = new ToggleButton("Chỉ việc hôm nay");
        // Without this the button only takes effect after some unrelated refresh.
        todayOnlyMode.selectedProperty().addListener((o, a, b) -> refreshHabits());
        Button weekBtn = new Button("Tổng kết tuần", new IconView("chart-column", 15));
        weekBtn.setOnAction(e -> showWeeklySummary());
        Button logBtn = new Button("Nhật ký thay đổi", new IconView("scroll-text", 15));
        logBtn.setOnAction(e -> showChangeLog());
        HBox header = toolbar(null, todayOnlyMode, weekBtn, logBtn);

        // ---- habits tab
        Button addHabit = new Button("Thói quen mới", new IconView("plus", 15));
        addHabit.getStyleClass().add("primary-btn");
        addHabit.setOnAction(e -> openHabitEditor(null));
        Label markHint = Card.dim("Bấm nút ngày để ghi nhận hoàn thành; chỉnh sửa lịch sử sẽ được ghi nhật ký.");
        // The scroll used to be built inside the VBox constructor and then pulled back
        // out of children().get(2) with a cast, four times. A name is cheaper than an
        // index: reorder the rows once and the casts silently grab the wrong node.
        javafx.scene.control.ScrollPane habitScroll = new javafx.scene.control.ScrollPane(habitCards);
        habitScroll.getStyleClass().add("fit");
        habitScroll.setFitToWidth(true);
        VBox.setVgrow(habitScroll, Priority.ALWAYS);
        VBox habitsPane = new VBox(10, addHabit, markHint, habitScroll);

        // ---- goals tab
        buildGoalTable();
        goalTable.setPlaceholder(EmptyState.withAction("flag", "Chưa có mục tiêu nào.",
                "Mục tiêu mới", () -> openGoalEditor(null)));
        Button addGoal = new Button("Mục tiêu mới", new IconView("plus", 15));
        addGoal.getStyleClass().add("primary-btn");
        addGoal.setOnAction(e -> openGoalEditor(null));
        Button editGoal = new Button("Sửa", new IconView("pencil", 15));
        editGoal.setOnAction(e -> editSelectedGoal());
        // A goal row is already selected before anyone looks for the pencil button,
        // so the button costs an extra trip across the screen for the same action.
        goalTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editSelectedGoal();
            }
        });
        Button delGoal = new Button("Xóa", new IconView("trash", 15));
        delGoal.setOnAction(e -> {
            Goal g = goalTable.getSelectionModel().getSelectedItem();
            if (g != null && Dialogs.confirm("Xóa mục tiêu", "Xóa \"" + g.title() + "\"?")) {
                ctx.habits().deleteGoal(g.id());
            }
        });

        tabPaneRef = new TabPane();
        TabPane tabs = tabPaneRef;
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Thói quen", habitsPane),
                new Tab("Mục tiêu", new VBox(10, new HBox(8, addGoal, editGoal, delGoal), goalTable)),
                new Tab("Nhật ký", logTable));
        VBox.setVgrow(tabs, Priority.ALWAYS);
        buildLogTable();

        watch(ctx.habits().changes());

        return page(header, tabs);
    }

    // ------------------------------------------------------------------ habits

    private void refreshHabits() {
        habitCards.getChildren().clear();
        List<Habit> habits = ctx.habits().habits(false);
        if (habits.isEmpty()) {
            habitCards.getChildren().add(EmptyState.withAction("flame",
                    "Chưa có thói quen nào.", "Thói quen mới", () -> openHabitEditor(null)));
            return;
        }
        boolean todayOnly = todayOnlyMode != null && todayOnlyMode.isSelected();
        LocalDate today = LocalDate.now();
        for (Habit h : habits) {
            if (todayOnly && !h.scheduledOn(today.getDayOfWeek())) {
                continue;
            }
            habitCards.getChildren().add(habitCard(h));
        }
        if (habitCards.getChildren().isEmpty()) {
            habitCards.getChildren().add(Card_(todayOnly ? "Hôm nay không có thói quen nào được lên lịch." : "Trống"));
        }
    }

    private VBox Card_(String text) {
        VBox box = new VBox(new Label(text));
        box.getStyleClass().add("card");
        return box;
    }

    private VBox habitCard(Habit h) {
        // Stats computed once per card build — completionRate alone used to run
        // twice per card, and these are O(window) loops over the log map.
        int streak = ctx.habits().currentStreak(h);
        int best = ctx.habits().bestStreak(h, 365);
        double rate30 = ctx.habits().completionRate(h, 30);

        VBox card = new VBox(6);
        card.getStyleClass().add("card");

        HBox header = new HBox(8, new Label(h.name),
                com.lifedesk.ui.components.Chips.chip("chuỗi " + streak + " ngày", "chip-done"),
                com.lifedesk.ui.components.Chips.chip("kỷ lục " + best + " ngày", "chip-info"),
                com.lifedesk.ui.components.Chips.chip(
                        Math.round(rate30 * 100) + "% / 30 ngày", "chip-medium"));
        header.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button edit = new Button("", new IconView("pencil", 15));
        edit.getStyleClass().add("icon-btn");
        edit.setTooltip(new Tooltip("Sửa thói quen"));
        edit.setOnAction(e -> openHabitEditor(h));
        Button del = new Button("", new IconView("trash", 15));
        del.getStyleClass().add("icon-btn");
        del.setTooltip(new Tooltip("Xóa thói quen"));
        del.setOnAction(e -> {
            if (Dialogs.confirm("Xóa thói quen", "Xóa \"" + h.name + "\"?")) {
                ctx.habits().deleteHabit(h.id);
            }
        });
        header.getChildren().addAll(spacer, edit, del);

        // 14-day grid of toggle buttons
        HBox days = new HBox(4);
        LocalDate today = LocalDate.now();
        Label daysCaption = Card.dim("14 ngày gần nhất:");
        for (int i = 13; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            boolean scheduled = h.scheduledOn(d.getDayOfWeek());
            boolean done = ctx.habits().isCompleted(h.id, d);
            String label = String.valueOf(d.getDayOfMonth());
            ToggleButton dayBtn = new ToggleButton(label);
            dayBtn.setSelected(done);
            dayBtn.setDisable(!scheduled || d.isAfter(today));
            dayBtn.setTooltip(new Tooltip(d + (scheduled ? "" : " (không lịch)") + (done ? " — đã điểm" : "")));
            dayBtn.getStyleClass().add(done ? "primary-btn" : "ghost-btn");
            dayBtn.setOnAction(e -> {
                boolean target = !ctx.habits().isCompleted(h.id, d);
                try {
                    if (d.isBefore(today)) {
                        String reason = Dialogs.prompt("Điều chỉnh lịch sử",
                                "Lý do chỉnh sửa hoàn thành ngày " + d + ":", "").orElse(null);
                        if (reason == null) {
                            dayBtn.setSelected(!target);
                            return;
                        }
                        ctx.habits().setCompletion(h.id, d, target, reason, "user");
                    } else {
                        ctx.habits().setCompletion(h.id, d, target, "Ghi nhận trong ngày", "user");
                    }
                    // setCompletion() fires the notifier this view watches, and that
                    // repaint is already coalesced — refreshing here too rebuilt every
                    // tile a second time inside the click.
                } catch (RuntimeException ex) {
                    Dialogs.error("Không ghi nhận được", ex.getMessage());
                    dayBtn.setSelected(!dayBtn.isSelected());
                }
            });
            days.getChildren().add(dayBtn);
        }
        ProgressBar rate = new ProgressBar(rate30);
        rate.setMaxWidth(Double.MAX_VALUE);
        rate.getStyleClass().add(rate30 > 0.7 ? "ok" : rate30 > 0.4 ? "warn" : "danger");
        card.getChildren().addAll(header, daysCaption, days, rate);
        return card;
    }

    void openHabitEditor(Habit existing) {
        javafx.scene.control.Dialog<Habit> dialog = Dialogs.newDialog();
        dialog.setTitle(existing == null ? "Thói quen mới" : "Sửa thói quen");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(Dialogs.SAVE,
                Dialogs.CANCEL);

        TextField name = new TextField(existing == null ? "" : existing.name);
        TextArea description = new TextArea(existing == null ? "" : existing.description);
        description.setPrefRowCount(3);
        description.setWrapText(true);
        List<CheckBox> dayChecks = new java.util.ArrayList<>();
        HBox daysBox = WeekdayChecks.row(
                existing == null ? java.util.Set.of(DayOfWeek.values()) : existing.scheduleDays, dayChecks);
        Label hint = Card.dim("Bỏ trống tất cả = hằng ngày");
        Spinner<Integer> reminderH = new Spinner<>(0, 23, existing != null && existing.reminderTime != null
                ? existing.reminderTime.getHour() : 8);
        Spinner<Integer> reminderM = new Spinner<>(0, 59, existing != null && existing.reminderTime != null
                ? existing.reminderTime.getMinute() : 0);
        CheckBox hasReminder = new CheckBox("Nhắc trong app");
        hasReminder.setSelected(existing != null && existing.reminderTime != null);

        VBox content = new VBox(10, FormGrid.labeled("Tên *", name), FormGrid.labeled("Mô tả", description),
                new VBox(4, new Label("Lịch thực hiện"), daysBox, hint),
                new HBox(8, hasReminder, new Label("giờ"), reminderH, reminderM));
        content.setPadding(new javafx.geometry.Insets(10));
        if (existing != null) {
            content.getChildren().add(new com.lifedesk.ui.components.NoteLinkPicker(ctx, "habits",
                    existing.id, "Ghi chú liên quan (quy tắc riêng, tiến độ…):"));
        }
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            Set<DayOfWeek> days = WeekdayChecks.selected(dayChecks);
            Habit h = existing == null ? new Habit() : existing;
            h.name = name.getText();
            h.description = description.getText();
            h.scheduleDays = days;
            h.reminderTime = hasReminder.isSelected() ? LocalTime.of(reminderH.getValue(), reminderM.getValue()) : null;
            return h;
        });
        dialog.showAndWait().ifPresent(h -> {
            try {
                if (existing == null) {
                    // addHabit returns the new habit; set the reminder on it
                    // instead of re-scanning the list by name.
                    Habit created = ctx.habits().addHabit(h.name, h.description, h.scheduleDays, "accent");
                    created.reminderTime = h.reminderTime;
                    if (h.reminderTime != null) {
                        ctx.habits().updateHabit(created, "Tạo mới", "user");
                    }
                } else {
                    ctx.habits().updateHabit(h, "Chỉnh sửa từ UI", "user");
                }
            } catch (RuntimeException ex) {
                Dialogs.error("Không lưu được", ex.getMessage());
            }
        });
    }

    private void editSelectedGoal() {
        Goal g = goalTable.getSelectionModel().getSelectedItem();
        if (g != null) {
            openGoalEditor(g);
        }
    }

    private void showChangeLog() {
        if (tabPaneRef != null) {
            tabPaneRef.getSelectionModel().select(2);
        }
        // Nothing was written, so nothing else on the page can have changed:
        // refresh() here rebuilt every habit tile just to open a tab.
        refreshLogs();
    }

    private void showWeeklySummary() {
        var rows = ctx.habits().weeklySummary();
        StringBuilder sb = new StringBuilder("Tổng kết tuần này (T2 → hôm nay):\n\n");
        for (var r : rows) {
            sb.append("• ").append(r.habitName()).append(": ").append(r.completed())
                    .append("/").append(r.scheduled()).append(" lần — chuỗi ").append(r.streak()).append(" ngày\n");
        }
        Dialogs.info("Tổng kết tuần", rows.isEmpty() ? "Chưa có thói quen nào." : sb.toString());
    }

    // ------------------------------------------------------------------ goals

    private void buildGoalTable() {
        TableColumn<Goal, String> titleCol = new TableColumn<>("Mục tiêu");
        titleCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().title()));
        TableColumn<Goal, String> targetCol = new TableColumn<>("Hạn");
        targetCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().targetDate() == null ? "—" : p.getValue().targetDate().toString()));
        TableColumn<Goal, String> modeCol = new TableColumn<>("Tiến độ theo");
        modeCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().progressMode() == ProgressMode.MANUAL ? "Tự đặt" : "Task liên kết"));
        TableColumn<Goal, Goal> progressCol = new TableColumn<>("Tiến độ");
        progressCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        progressCol.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            private final ProgressBar bar = new ProgressBar();
            private final Label label = new Label();

            @Override
            protected void updateItem(Goal item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                int pct = computeProgress(item);
                label.setText(pct + "%");
                bar.setProgress(pct / 100.0);
                bar.getStyleClass().add(pct >= 100 ? "ok" : pct > 50 ? "warn" : "danger");
                HBox box = new HBox(8, bar, label);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        TableColumn<Goal, String> statusCol = new TableColumn<>("Trạng thái");
        statusCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().status().name()));
        goalTable.getColumns().setAll(List.of(titleCol, targetCol, modeCol, progressCol, statusCol));
        goalTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private int computeProgress(Goal g) {
        return com.lifedesk.habits.GoalProgress.percent(g, ctx.tasks().all());
    }

    void openGoalEditor(Goal existing) {
        javafx.scene.control.Dialog<Goal> dialog = Dialogs.newDialog();
        dialog.setTitle(existing == null ? "Mục tiêu mới" : "Sửa mục tiêu");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(Dialogs.SAVE,
                Dialogs.CANCEL);

        TextField title = new TextField(existing == null ? "" : existing.title());
        TextArea description = new TextArea(existing == null ? "" : existing.description());
        description.setPrefRowCount(3);
        description.setWrapText(true);
        DatePicker target = new DatePicker(existing == null ? LocalDate.now().plusMonths(1) : existing.targetDate());
        ComboBox<GoalStatus> status = new ComboBox<>();
        status.getItems().addAll(GoalStatus.values());
        status.setValue(existing == null ? GoalStatus.ACTIVE : existing.status());
        ComboBox<ProgressMode> mode = new ComboBox<>();
        mode.getItems().addAll(ProgressMode.values());
        mode.setValue(existing == null ? ProgressMode.MANUAL : existing.progressMode());
        Spinner<Integer> progress = new Spinner<>(0, 100, existing == null ? 0 : existing.manualProgress());
        ComboBox<com.lifedesk.tasks.Task> taskPick = new ComboBox<>();
        taskPick.getItems().addAll(ctx.tasks().all());
        Combos.label(taskPick, task -> task.title);
        Set<String> linked = existing == null ? new LinkedHashSet<>() : new LinkedHashSet<>(existing.linkedTaskIds());
        Label linkedLabel = new Label("Đã liên kết: " + linked.size() + " task");
        Button toggleLink = new Button("Bật/tắt liên kết task đang chọn");
        toggleLink.setOnAction(e -> {
            var t = taskPick.getValue();
            if (t != null) {
                if (!linked.add(t.id)) {
                    linked.remove(t.id);
                }
                linkedLabel.setText("Đã liên kết: " + linked.size() + " task");
            }
        });

        VBox content = new VBox(10, FormGrid.labeled("Tên mục tiêu *", title), FormGrid.labeled("Mô tả", description),
                new HBox(10, FormGrid.labeled("Hạn", target), FormGrid.labeled("Trạng thái", status)),
                new HBox(10, FormGrid.labeled("Tiến độ theo", mode), FormGrid.labeled("Tự đặt (%)", progress)),
                new VBox(4, new HBox(8, taskPick, toggleLink), linkedLabel));
        content.setPadding(new javafx.geometry.Insets(10));
        if (existing != null) {
            content.getChildren().add(new com.lifedesk.ui.components.NoteLinkPicker(ctx, "goals",
                    existing.id, "Ghi chú liên quan (kế hoạch chi tiết…):"));
        }
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            Goal g = existing == null ? new Goal() : existing;
            g.title = title.getText();
            g.description = description.getText();
            g.targetDate = target.getValue();
            g.status = status.getValue();
            g.progressMode = mode.getValue();
            g.manualProgress = progress.getValue();
            g.linkedTaskIds = new LinkedHashSet<>(linked);
            return g;
        });
        dialog.showAndWait().ifPresent(g -> {
            try {
                if (existing == null) {
                    // addGoal returns the new goal; set the rest of the fields
                    // on it instead of re-scanning the list by title.
                    Goal created = ctx.habits().addGoal(g.title, g.description, g.targetDate);
                    created.status = g.status;
                    created.progressMode = g.progressMode;
                    created.manualProgress = g.manualProgress;
                    created.linkedTaskIds = g.linkedTaskIds;
                    ctx.habits().updateGoal(created, "Tạo mới", "user");
                } else {
                    ctx.habits().updateGoal(g, "Chỉnh sửa từ UI", "user");
                }
            } catch (RuntimeException ex) {
                Dialogs.error("Không lưu được", ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ change log

    private void buildLogTable() {
        TableColumn<ChangeLogEntry, String> at = new TableColumn<>("Thời điểm");
        at.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().at.toString()));
        TableColumn<ChangeLogEntry, String> entity = new TableColumn<>("Đối tượng");
        entity.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().entityType + ": " + p.getValue().entityName));
        TableColumn<ChangeLogEntry, String> field = new TableColumn<>("Trường");
        field.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().field));
        TableColumn<ChangeLogEntry, String> change = new TableColumn<>("Thay đổi");
        change.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().oldValue + " → " + p.getValue().newValue));
        TableColumn<ChangeLogEntry, String> reason = new TableColumn<>("Lý do");
        reason.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().reason + " (" + p.getValue().actor + ")"));
        logTable.getColumns().setAll(List.of(at, entity, field, change, reason));
        logTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        logTable.setPlaceholder(new EmptyState("scroll-text",
                "Chưa có thay đổi nào. Nhật ký ghi lại mỗi lần bạn sửa hoặc xoá thói quen, mục tiêu."));
    }

    @Override
    public void onShow() {
        // Only re-read when the module actually changed since the last paint.
        refreshWhenChanged(ctx.habits().changes(), ctx.tasks().changes());
    }

    @Override
    protected void refresh() {
        refreshHabits();
        goalTable.getItems().setAll(ctx.habits().goals());
        refreshLogs();
    }

    private void refreshLogs() {
        logTable.getItems().setAll(ctx.habits().changeLog().stream()
                .sorted(java.util.Comparator.comparing((ChangeLogEntry e) -> e.at).reversed())
                .limit(100).toList());
    }


    @Override
    public void openRecord(String recordId) {
        ctx.habits().goals().stream().filter(g -> g.id().equals(recordId)).findFirst()
                .ifPresent(goalTable.getSelectionModel()::select);
    }
}
