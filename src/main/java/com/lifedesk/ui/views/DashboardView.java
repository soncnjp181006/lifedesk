package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.finance.FinanceService;
import com.lifedesk.habits.Habit;
import com.lifedesk.learning.LearningService;
import com.lifedesk.tasks.Task;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.Card;
import com.lifedesk.ui.components.Chips;
import com.lifedesk.ui.components.EmptyState;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard: toggleable, orderable widgets over every module, driven by a
 * date-range filter (day / week / month / custom).
 */
public class DashboardView extends View {
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM");

    private final javafx.scene.layout.GridPane widgetGrid = new javafx.scene.layout.GridPane();
    private final javafx.scene.layout.ColumnConstraints widgetCol = new javafx.scene.layout.ColumnConstraints();
    private LocalDate rangeFrom = LocalDate.now();
    private LocalDate rangeTo = LocalDate.now();
    private final Map<String, Node> widgets = new LinkedHashMap<>();

    public DashboardView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "DASHBOARD";
    }

    @Override
    protected Node build() {
        initWidgetDefs();

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox titleBlock = new VBox(1);
        Label title = new Label("Dashboard");
        title.getStyleClass().add("title-label");
        Label subtitle = new Label("Tổng quan công việc, tài chính, thói quen và học tập của bạn");
        subtitle.getStyleClass().add("page-subtitle");
        titleBlock.getChildren().addAll(title, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ComboBox<String> preset = new ComboBox<>();
        preset.getItems().addAll("Hôm nay", "Tuần này", "Tháng này", "Tùy chọn…");
        preset.setValue("Hôm nay");
        DatePicker from = new DatePicker(rangeFrom);
        DatePicker to = new DatePicker(rangeTo);
        from.setDisable(true);
        to.setDisable(true);
        preset.setOnAction(e -> {
            switch (preset.getValue()) {
                case "Hôm nay" -> {
                    rangeFrom = LocalDate.now();
                    rangeTo = LocalDate.now();
                }
                case "Tuần này" -> {
                    rangeFrom = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
                    rangeTo = rangeFrom.plusDays(6);
                }
                case "Tháng này" -> {
                    rangeFrom = LocalDate.now().withDayOfMonth(1);
                    rangeTo = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());
                }
                default -> {
                    from.setDisable(false);
                    to.setDisable(false);
                }
            }
            from.setValue(rangeFrom);
            to.setValue(rangeTo);
            if (!"Tùy chọn…".equals(preset.getValue())) {
                from.setDisable(true);
                to.setDisable(true);
                refresh();
            }
        });
        from.setOnAction(e -> {
            rangeFrom = from.getValue();
            refresh();
        });
        to.setOnAction(e -> {
            rangeTo = to.getValue();
            refresh();
        });

        Button manage = new Button("Quản lý widget");
        manage.setOnAction(e -> showWidgetManager());

        header.getChildren().addAll(titleBlock, spacer, new Label("Khoảng:"), preset,
                new Label("từ"), from, new Label("đến"), to, manage);

        widgetGrid.setHgap(14);
        widgetGrid.setVgap(14);
        widgetGrid.setMinWidth(0);
        widgetGrid.setMaxWidth(Double.MAX_VALUE);
        widgetCol.setPercentWidth(100.0 / 3);
        widgetCol.setHgrow(Priority.ALWAYS);
        widgetGrid.getColumnConstraints().addAll(widgetCol, widgetCol, widgetCol);

        VBox root = new VBox(12, header, widgetGrid);
        VBox.setVgrow(widgetGrid, Priority.ALWAYS);
        return root;
    }

    private void initWidgetDefs() {
        Map<String, Boolean> enabled = ctx.config().dashboardWidgets;
        List<String> order = ctx.config().dashboardWidgetOrder;
        List<String> all = List.of("w_today", "w_overdue", "w_upcoming", "w_finance", "w_budget",
                "w_habits", "w_goals", "w_learning", "w_health");
        if (order.isEmpty() || !order.containsAll(all)) {
            order.clear();
            order.addAll(all);
        }
        for (String id : all) {
            enabled.putIfAbsent(id, true);
        }
    }

    @Override
    public void onShow() {
        refresh();
    }

    private void refresh() {
        widgets.clear();
        widgets.put("w_today", todayWidget());
        widgets.put("w_overdue", overdueWidget());
        widgets.put("w_upcoming", upcomingWidget());
        widgets.put("w_finance", financeWidget());
        widgets.put("w_budget", budgetWidget());
        widgets.put("w_habits", habitsWidget());
        widgets.put("w_goals", goalsWidget());
        widgets.put("w_learning", learningWidget());
        widgets.put("w_health", healthWidget());

        widgetGrid.getChildren().clear();
        int col = 0;
        int row = 0;
        for (String id : ctx.config().dashboardWidgetOrder) {
            Boolean on = ctx.config().dashboardWidgets.get(id);
            Node w = widgets.get(id);
            if (on != null && on && w != null) {
                if (w instanceof Card c) {
                    c.setMaxWidth(Double.MAX_VALUE);
                    c.setMinHeight(140);
                    GridPane.setHgrow(c, Priority.ALWAYS);
                    GridPane.setFillWidth(c, true);
                }
                widgetGrid.add(w, col, row);
                col++;
                if (col == 3) {
                    col = 0;
                    row++;
                }
            }
        }
        if (widgetGrid.getChildren().isEmpty()) {
            widgetGrid.add(new EmptyState("puzzle",
                    "Tất cả widget đang tắt. Bấm \"Quản lý widget\" để bật lại."), 0, 0, 3, 1);
        }
    }

    // ------------------------------------------------------------------ widgets

    private Card todayWidget() {
        Card card = new Card("Việc hôm nay");
        List<Task> list = new ArrayList<>(ctx.tasks().dueToday());
        if (list.isEmpty()) {
            card.content(Card.dim("Không có việc đến hạn hôm nay"));
            return card;
        }
        VBox box = new VBox(4);
        int shown = 0;
        for (Task t : list) {
            if (t.status == com.lifedesk.tasks.TaskStatus.DONE) {
                continue;
            }
            HBox row = new HBox(6, Chips.priority(t.priority.name()),
                    new Label(t.title + (t.dueTime != null ? " " + t.dueTime : "")));
            row.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(row);
            if (++shown >= 6) {
                break;
            }
        }
        if (box.getChildren().isEmpty()) {
            box.getChildren().add(Card.dim("Tất cả việc hôm nay đã xong"));
        }
        card.content(box);
        return card;
    }

    private Card overdueWidget() {
        Card card = new Card("Quá hạn");
        List<Task> list = new ArrayList<>(ctx.tasks().overdue());
        Label big = new Label(String.valueOf(list.size()));
        big.getStyleClass().addAll("metric-big", list.isEmpty() ? "metric-pos" : "metric-neg");
        if (list.isEmpty()) {
            card.content(big, Card.dim("Không có việc trễ hạn"));
        } else {
            VBox box = new VBox(4);
            list.stream().limit(5).forEach(t -> {
                HBox row = new HBox(6, Chips.overdue("trễ " + t.dueDate.format(DF)), new Label(t.title));
                row.setAlignment(Pos.CENTER_LEFT);
                box.getChildren().add(row);
            });
            card.content(big, box);
        }
        return card;
    }

    private Card upcomingWidget() {
        Card card = new Card("Sắp tới (7 ngày)");
        List<Task> list = new ArrayList<>(ctx.tasks().upcoming(7));
        if (list.isEmpty()) {
            card.content(Card.dim("Trống trong 7 ngày tới"));
            return card;
        }
        VBox box = new VBox(4);
        list.stream().limit(7).forEach(t -> box.getChildren().add(
                new Label(t.dueDate.format(DF) + "  " + t.title)));
        card.content(box);
        return card;
    }

    private Card financeWidget() {
        // A single-day line chart is always flat - when the filter is one day,
        // show the whole current month instead (what real dashboards do).
        java.time.LocalDate chartFrom = rangeFrom;
        java.time.LocalDate chartTo = rangeTo;
        String chartTitle = rangeFrom.format(DF) + " → " + rangeTo.format(DF);
        if (chartFrom.equals(chartTo)) {
            chartFrom = rangeFrom.withDayOfMonth(1);
            chartTo = rangeFrom.withDayOfMonth(rangeFrom.lengthOfMonth());
            chartTitle = "tháng " + rangeFrom.format(java.time.format.DateTimeFormatter.ofPattern("MM/yyyy"));
        }
        Card card = new Card("Thu chi (" + chartTitle + ")");
        final java.time.LocalDate axisFrom = chartFrom;
        long span = chartTo.toEpochDay() - chartFrom.toEpochDay();
        NumberAxis x = new NumberAxis();
        x.setLabel("Ngày");
        x.setMinorTickVisible(false);
        x.setLowerBound(0);
        x.setUpperBound(span);
        x.setAutoRanging(false);
        x.setTickUnit(Math.max(1, Math.round(span / 5.0)));
        // Raw epoch offsets render as "0.0, 2.5, 5.0"; people read dates.
        x.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Number n) {
                return axisFrom.plusDays(Math.round(n.doubleValue())).format(DF);
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        NumberAxis y = new NumberAxis();
        y.setLabel("VND");
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setLegendVisible(true);
        chart.setCreateSymbols(false);
        chart.setPrefHeight(230);
        chart.setMinHeight(210);

        List<FinanceService.DayPoint> series = ctx.finance().dailySeries(chartFrom, chartTo);
        XYChart.Series<Number, Number> income = new XYChart.Series<>();
        income.setName("Thu nhập");
        XYChart.Series<Number, Number> expense = new XYChart.Series<>();
        expense.setName("Chi tiêu");
        long start = chartFrom.toEpochDay();
        for (FinanceService.DayPoint p : series) {
            income.getData().add(new XYChart.Data<>(p.date().toEpochDay() - start, p.income().doubleValue()));
            expense.getData().add(new XYChart.Data<>(p.date().toEpochDay() - start, p.expense().doubleValue()));
        }
        chart.getData().addAll(income, expense);
        card.content(chart);
        return card;
    }

    private Card budgetWidget() {
        Card card = new Card("Ngân sách " + java.time.YearMonth.now());
        var statuses = ctx.finance().budgetStatus(java.time.YearMonth.now());
        if (statuses.isEmpty()) {
            card.content(Card.dim("Chưa đặt ngân sách cho tháng này (tab Tài chính)"));
            return card;
        }
        VBox box = new VBox(6);
        for (var s : statuses) {
            Label name = new Label(s.categoryName() + ": " + s.spent().toPlainString() + " / "
                    + s.budget().limitAmount.toPlainString() + " VND");
            ProgressBar bar = new ProgressBar(Math.min(1.0, Math.max(0, s.usedRatio())));
            bar.setMaxWidth(Double.MAX_VALUE);
            if (s.usedRatio() > 1) {
                bar.getStyleClass().add("danger");
            } else if (s.usedRatio() > 0.8) {
                bar.getStyleClass().add("warn");
            } else {
                bar.getStyleClass().add("ok");
            }
            box.getChildren().addAll(name, bar);
        }
        card.content(box);
        return card;
    }

    private Card habitsWidget() {
        Card card = new Card("Thói quen");
        List<Habit> habits = ctx.habits().habits(false);
        if (habits.isEmpty()) {
            card.content(Card.dim("Chưa có thói quen nào"));
            return card;
        }
        VBox box = new VBox(6);
        for (Habit h : habits) {
            double rate = ctx.habits().completionRate(h, 30);
            HBox row = new HBox(8, new Label(h.name),
                    Chips.chip("chuỗi " + ctx.habits().currentStreak(h) + " ngày", "chip-done"));
            row.setAlignment(Pos.CENTER_LEFT);
            ProgressBar bar = new ProgressBar(rate);
            bar.setMaxWidth(Double.MAX_VALUE);
            bar.getStyleClass().add("ok");
            box.getChildren().addAll(row, bar);
        }
        card.content(box);
        return card;
    }

    private Card goalsWidget() {
        Card card = new Card("Mục tiêu");
        var goals = ctx.habits().goals().stream()
                .filter(g -> g.status() == com.lifedesk.habits.GoalStatus.ACTIVE).toList();
        if (goals.isEmpty()) {
            card.content(Card.dim("Không có mục tiêu đang chạy"));
            return card;
        }
        VBox box = new VBox(6);
        for (var g : goals) {
            int progress = computeGoalProgress(g);
            HBox row = new HBox(8, new Label(g.title()), Chips.chip(progress + "%",
                    progress >= 100 ? "chip-done" : "chip-medium"));
            row.setAlignment(Pos.CENTER_LEFT);
            ProgressBar bar = new ProgressBar(progress / 100.0);
            bar.setMaxWidth(Double.MAX_VALUE);
            box.getChildren().addAll(row, bar);
        }
        card.content(box);
        return card;
    }

    private int computeGoalProgress(com.lifedesk.habits.Goal g) {
        if (g.progressMode() == com.lifedesk.habits.ProgressMode.MANUAL) {
            return g.manualProgress();
        }
        long total = g.linkedTaskIds().size();
        if (total == 0) {
            return 0;
        }
        long done = ctx.tasks().all().stream()
                .filter(t -> g.linkedTaskIds().contains(t.id) && t.status == com.lifedesk.tasks.TaskStatus.DONE)
                .count();
        return (int) Math.round(100.0 * done / total);
    }

    private Card learningWidget() {
        Card card = new Card("Học tập");
        LearningService ls = ctx.learning();
        long minutes = ls.minutesBetween(LocalDate.now().minusDays(6), LocalDate.now());
        VBox box = new VBox(4,
                new Label("Thẻ đến hạn ôn tập: " + ls.dueCount()),
                new Label("Tổng thẻ: " + ls.cardCount()),
                new Label("Thời gian học 7 ngày: " + (minutes / 60) + "h" + (minutes % 60) + "p"));
        var active = ls.courses().stream()
                .filter(c -> c.status() == com.lifedesk.learning.CourseStatus.IN_PROGRESS).limit(5).toList();
        for (var c : active) {
            box.getChildren().add(new Label("• " + c.title() + " (" + c.progressPercent() + "%)"));
        }
        card.content(box);
        return card;
    }

    private Card healthWidget() {
        Card card = new Card("Sức khỏe 30 ngày");
        Double sleep = ctx.health().averageDays(com.lifedesk.health.HealthService.HealthField.SLEEP, 30);
        Double exercise = ctx.health().averageDays(com.lifedesk.health.HealthService.HealthField.EXERCISE, 30);
        Double weight = ctx.health().averageDays(com.lifedesk.health.HealthService.HealthField.WEIGHT, 30);
        VBox box = new VBox(4);
        box.getChildren().add(new Label("Ngủ: " + (sleep == null ? "—" : sleep + " giờ/đêm")));
        box.getChildren().add(new Label("Vận động: " + (exercise == null ? "—" : Math.round(exercise) + " phút/ngày")));
        box.getChildren().add(new Label("Cân nặng TB: " + (weight == null ? "—" : weight + " kg")));
        box.getChildren().add(Card.dim("Ghi nhận thủ công — không phải tư vấn y tế."));
        card.content(box);
        return card;
    }

    // ------------------------------------------------------------------ widget manager

    private void showWidgetManager() {
        VBox box = new VBox(8);
        Map<String, Boolean> enabled = ctx.config().dashboardWidgets;
        List<String> order = ctx.config().dashboardWidgetOrder;
        List<String> labels = List.of("w_today", "w_overdue", "w_upcoming", "w_finance", "w_budget",
                "w_habits", "w_goals", "w_learning", "w_health");
        Map<String, String> names = Map.of(
                "w_today", "Việc hôm nay", "w_overdue", "Quá hạn", "w_upcoming", "Sắp tới",
                "w_finance", "Biểu đồ thu chi", "w_budget", "Ngân sách", "w_habits", "Thói quen",
                "w_goals", "Mục tiêu", "w_learning", "Học tập", "w_health", "Sức khỏe");

        List<javafx.scene.control.CheckBox> checks = new ArrayList<>();
        for (String id : order) {
            javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox(names.getOrDefault(id, id));
            cb.setSelected(Boolean.TRUE.equals(enabled.get(id)));
            cb.setUserData(id);
            checks.add(cb);
            box.getChildren().add(cb);
        }
        Label hint = new Label("Thứ tự hiển thị = thứ tự danh sách. Tắt/bật rồi bấm Lưu.");
        hint.getStyleClass().add("dim-label");

        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle("Quản lý widget dashboard");
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(new VBox(8, hint, box));
        alert.showAndWait().ifPresent(bt -> {
            if (bt.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                for (var cb : checks) {
                    enabled.put((String) cb.getUserData(), cb.isSelected());
                }
                refresh();
            }
        });
    }
}
