package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.finance.FinanceService;
import com.lifedesk.habits.Habit;
import com.lifedesk.learning.LearningService;
import com.lifedesk.tasks.Task;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.Dialogs;
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
import javafx.scene.control.Tooltip;
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
    /** The four headline numbers above the widget grid. */
    private VBox metricStrip;

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

        // No page title block: the rail already names this page, and the landing
        // screen spent two lines saying it again.
        ComboBox<String> preset = new ComboBox<>();
        preset.getItems().addAll("Hôm nay", "Tuần này", "Tháng này", "Tùy chọn…");
        preset.setValue("Hôm nay");
        DatePicker from = new DatePicker(rangeFrom);
        DatePicker to = new DatePicker(rangeTo);
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

        // One quiet control strip instead of nine loose controls hanging off the
        // heading: the range is secondary information and now sits like it.
        Label rangeLabel = new Label("Khoảng");
        rangeLabel.getStyleClass().add("toolbar-label");
        Label fromLabel = new Label("từ");
        fromLabel.getStyleClass().add("toolbar-label");
        Label toLabel = new Label("đến");
        toLabel.getStyleClass().add("toolbar-label");
        from.getStyleClass().add("compact-control");
        to.getStyleClass().add("compact-control");
        preset.getStyleClass().add("compact-control");
        HBox rangeStrip = new HBox(8, rangeLabel, preset, fromLabel, from, toLabel, to);
        rangeStrip.setAlignment(Pos.CENTER_LEFT);
        rangeStrip.getStyleClass().add("toolbar-strip");

        // The two pickers only mean anything in "Tùy chọn…". They used to stay in the
        // header permanently, greyed out — 300px of dead controls on every visit — and
        // the old handler also forgot to repaint when you switched *to* custom.
        Runnable showCustom = () -> {
            boolean custom = "Tùy chọn…".equals(preset.getValue());
            for (Node cell : new Node[]{fromLabel, from, toLabel, to}) {
                cell.setVisible(custom);
                cell.setManaged(custom);
            }
        };
        preset.setOnAction(e -> {
            LocalDate today = LocalDate.now();
            switch (preset.getValue()) {
                case "Hôm nay" -> {
                    rangeFrom = today;
                    rangeTo = today;
                }
                case "Tuần này" -> {
                    rangeFrom = today.with(java.time.DayOfWeek.MONDAY);
                    rangeTo = rangeFrom.plusDays(6);
                }
                case "Tháng này" -> {
                    rangeFrom = today.withDayOfMonth(1);
                    rangeTo = today.withDayOfMonth(today.lengthOfMonth());
                }
                default -> {
                }
            }
            from.setValue(rangeFrom);
            to.setValue(rangeTo);
            showCustom.run();
            refresh();
        });
        showCustom.run();

        HBox header = toolbar(null, rangeStrip, manage);

        widgetGrid.setHgap(14);
        widgetGrid.setVgap(14);
        widgetGrid.setMinWidth(0);
        widgetGrid.setMaxWidth(Double.MAX_VALUE);
        widgetCol.setPercentWidth(100.0 / 3);
        widgetCol.setHgrow(Priority.ALWAYS);
        widgetGrid.getColumnConstraints().addAll(widgetCol, widgetCol, widgetCol);

        // One row of equal tiles. An HBox with hgrow shares out only the *spare*
        // width on top of each tile's own content width, so the tile holding the
        // longest sentence stayed the widest and the strip read as a table with one
        // fat column; the cards here exist to be compared, so they must be the same
        // size whatever they say.
        metricStrip = new VBox();
        metricStrip.setMaxWidth(Double.MAX_VALUE);

        VBox root = page(header, metricStrip, widgetGrid);
        VBox.setVgrow(widgetGrid, Priority.ALWAYS);
        root.getStyleClass().add("dashboard");
        return root;
    }

    private void initWidgetDefs() {
        Map<String, Boolean> enabled = ctx.config().dashboardWidgets;
        List<String> order = ctx.config().dashboardWidgetOrder;
        List<String> all = List.of("w_today", "w_overdue", "w_upcoming", "w_finance", "w_budget",
                "w_habits", "w_goals", "w_learning", "w_health", "w_notes");
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
        // Same revision check every other data view uses. The dashboard used to
        // recompute a fingerprint of the whole dataset on every visit — all streaks
        // (365 days each), month totals, budget rows, due cards, health averages —
        // to decide whether to redraw itself.
        // Revisions say nothing about midnight. Every widget here is keyed to
        // "today" — due tasks, this month's budget rows, streaks — so a dashboard
        // left open over the date line used to keep yesterday's numbers until some
        // service happened to write.
        java.time.LocalDate now = java.time.LocalDate.now();
        boolean newDay = paintedOn == null || !paintedOn.equals(now);
        paintedOn = now;
        if (newDay) {
            refresh();
            return;
        }
        refreshWhenChanged(ctx.tasks().changes(), ctx.finance().changes(), ctx.habits().changes(),
                ctx.learning().changes(), ctx.health().changes(), ctx.notes().changes());
    }

    private java.time.LocalDate paintedOn;

    @Override
    public void onThemeChanged() {
        // The charts embed the accent color in their HTML, so a palette swap must
        // repaint even when no data moved.
        scheduleRefresh();
    }

    @Override
    protected void refresh() {
        rebuildMetrics();
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
        widgets.put("w_notes", notesWidget());

        widgetGrid.getChildren().clear();
        int col = 0;
        int row = 0;
        for (String id : ctx.config().dashboardWidgetOrder) {
            Boolean on = ctx.config().dashboardWidgets.get(id);
            Node w = widgets.get(id);
            if (on != null && on && w != null) {
                if (w instanceof Card c) {
                    c.setMaxWidth(Double.MAX_VALUE);
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
            widgetGrid.add(EmptyState.withAction("puzzle", "Tất cả widget đang tắt.",
                    "Quản lý widget", () -> showWidgetManager()), 0, 0, 3, 1);
        }
    }


    // ------------------------------------------------------------------ metric strip

    private void rebuildMetrics() {
        if (metricStrip == null) {
            return;
        }
        int dueToday = 0;
        for (Task t : ctx.tasks().dueToday()) {
            if (t.status != com.lifedesk.tasks.TaskStatus.DONE) {
                dueToday++;
            }
        }
        int overdue = ctx.tasks().overdue().size();
        var month = ctx.finance().monthSummary(java.time.YearMonth.now());
        int cards = ctx.learning().dueCount();

        metricStrip.getChildren().setAll(com.lifedesk.ui.Fx.equalTiles(12,
                metricTile("Việc hôm nay", String.valueOf(dueToday), "việc",
                        overdue == 0 ? "không có việc quá hạn" : overdue + " việc đã quá hạn",
                        overdue > 0, () -> main.showView(MainWindow.ViewId.TASKS)),
                metricTile("Số dư tháng " + java.time.YearMonth.now().getMonthValue(),
                        compactVnd(month.net()), "",
                        "thu " + compactVnd(month.income()) + " · chi " + compactVnd(month.expense()),
                        month.net().signum() < 0, () -> main.showView(MainWindow.ViewId.FINANCE)),
                metricTile("Thẻ đến hạn", String.valueOf(cards), "thẻ",
                        cards == 0 ? "không có gì phải ôn hôm nay" : "khoảng " + Math.max(5, cards * 2) + " phút ôn tập",
                        false, () -> main.showView(MainWindow.ViewId.LEARNING)),
                metricTile("Ghi chú", String.valueOf(ctx.notes().list().size()), "ghi chú",
                        ctx.notes().list().isEmpty() ? "chưa có ghi chú nào" : "mở tab Ghi chú để viết tiếp",
                        false, () -> main.showView(MainWindow.ViewId.NOTES))));
    }

    /** A headline number the user can click through to the module it summarises. */
    private VBox metricTile(String caption, String value, String unit, String note,
                            boolean alert, Runnable onClick) {
        Label cap = new Label(caption);
        cap.getStyleClass().add("metric-caption");
        Label val = new Label(value);
        val.getStyleClass().add("metric-value");
        HBox valueRow = new HBox(6, val);
        valueRow.setAlignment(Pos.BASELINE_LEFT);
        if (!unit.isEmpty()) {
            Label u = new Label(unit);
            u.getStyleClass().add("metric-unit");
            valueRow.getChildren().add(u);
        }
        Label n = new Label(note);
        n.getStyleClass().add("metric-note");
        n.setWrapText(false);
        if (alert) {
            val.getStyleClass().add("metric-neg");
        }
        // Spacing and the hand cursor come from .metric-tile in CSS: the constructor
        // gap was dead against -fx-spacing, and setting the cursor on mouse-enter only
        // (no mouse-exit) left the hand pointer stuck on the tile after the mouse left.
        VBox tile = new VBox(cap, valueRow, n);
        tile.getStyleClass().add("metric-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        tile.setOnMouseClicked(e -> onClick.run());
        return tile;
    }

    /** 12.400.000 → "12,4 tr" — a 30px number cannot show nine digits. */
    private static String compactVnd(java.math.BigDecimal amount) {
        boolean negative = amount.signum() < 0;
        long abs = Math.abs(amount.longValue());
        String out;
        if (abs >= 1_000_000_000L) {
            out = String.format(java.util.Locale.US, "%.1f", abs / 1_000_000_000.0).replace('.', ',') + " tỷ";
        } else if (abs >= 1_000_000L) {
            out = String.format(java.util.Locale.US, "%.1f", abs / 1_000_000.0).replace('.', ',') + " tr";
        } else if (abs >= 1_000L) {
            out = String.format(java.util.Locale.US, "%,d", abs).replace(',', '.') + " đ";
        } else {
            out = abs + " đ";
        }
        return (negative ? "-" : "") + out;
    }

    /**
     * A line inside a widget card: the row is the part that shortens when the column
     * is narrow, and the whole text (title, date, figures) stays readable on hover.
     * Without the tooltip a truncated task name is simply lost.
     */
    private static Label rowLine(String text) {
        return Fx.shrinkable(new Label(text));
    }

    /** Compact in-card empty state: states the gap and offers the way out of it. */
    private VBox emptyBlock(String text, String actionLabel, Runnable action) {
        Label msg = new Label(text);
        msg.getStyleClass().add("card-empty-text");
        msg.setWrapText(true);
        VBox box = new VBox(4, msg);
        box.getStyleClass().add("card-empty");
        if (actionLabel != null) {
            Button b = new Button(actionLabel + " →");
            b.getStyleClass().add("card-action");
            b.setOnAction(e -> action.run());
            box.getChildren().add(b);
        }
        return box;
    }

    /** Label on the left, number on the right — the card equivalent of a table row. */
    private HBox metricRow(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("metric-key");
        Label v = new Label(value);
        v.getStyleClass().add("metric-val");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(8, k, spacer, v);
        row.getStyleClass().add("metric-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ------------------------------------------------------------------ widgets

    private Card todayWidget() {
        Card card = new Card("Việc hôm nay");
        List<Task> list = new ArrayList<>(ctx.tasks().dueToday());
        if (list.isEmpty()) {
            card.content(emptyBlock("Không có việc đến hạn hôm nay.", "Mở công việc",
                    () -> main.showView(MainWindow.ViewId.TASKS)));
            return card;
        }
        VBox box = new VBox(4);
        int shown = 0;
        for (Task t : list) {
            if (t.status == com.lifedesk.tasks.TaskStatus.DONE) {
                continue;
            }
            HBox row = new HBox(6, Chips.priority(t.priority.name()), rowLine(
                    t.title + (t.dueTime != null ? " " + t.dueTime : "")));
            row.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(row);
            if (++shown >= 6) {
                break;
            }
        }
        if (box.getChildren().isEmpty()) {
            box.getChildren().add(emptyBlock("Tất cả việc hôm nay đã xong.", "Xem công việc",
                    () -> main.showView(MainWindow.ViewId.TASKS)));
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
                HBox row = new HBox(6, Chips.overdue("trễ " + t.dueDate.format(DF)),
                        rowLine(t.title));
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
                rowLine(t.dueDate.format(DF) + "  " + t.title)));
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
        // Ticks render as real dates, not raw epoch offsets (see DateAxes).
        long span = chartTo.toEpochDay() - chartFrom.toEpochDay();
        NumberAxis x = com.lifedesk.ui.components.DateAxes.epochDay("Ngày", chartFrom, chartTo,
                Math.max(1, Math.round(span / 5.0)));
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
        boolean hasData = series.stream().anyMatch(p -> p.income().signum() != 0 || p.expense().signum() != 0);
        if (!hasData) {
            // An empty axis pair with a flat zero line looks like a broken chart.
            // Say what is missing and link to the screen that fixes it.
            card.content(emptyBlock("Chưa có giao dịch nào trong " + chartTitle + ".",
                    "Thêm giao dịch", () -> main.showView(MainWindow.ViewId.FINANCE)));
            return card;
        }
        card.content(chart);
        return card;
    }

    private Card budgetWidget() {
        Card card = new Card("Ngân sách " + java.time.YearMonth.now());
        var statuses = ctx.finance().budgetStatus(java.time.YearMonth.now());
        if (statuses.isEmpty()) {
            card.content(emptyBlock("Chưa đặt ngân sách cho tháng này.", "Đặt ngân sách",
                    () -> main.showView(MainWindow.ViewId.FINANCE)));
            return card;
        }
        VBox box = new VBox(6);
        for (var s : statuses) {
            // Compact like every other money figure on this page: "2803569 / 4000000 VND"
            // is eighteen characters wide and the row elided the limit ("…V"). The exact
            // figures stay one hover away.
            Label name = Fx.shrinkable(new Label(s.categoryName() + " · "
                    + compactVnd(s.spent()) + " / " + compactVnd(s.budget().limitAmount)));
            name.setTooltip(new Tooltip(s.categoryName() + ": " + s.spent().toPlainString()
                    + " / " + s.budget().limitAmount.toPlainString() + " VND"));
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
            card.content(emptyBlock("Chưa có thói quen nào để theo dõi.", "Tạo thói quen",
                    () -> main.showView(MainWindow.ViewId.HABITS)));
            return card;
        }
        VBox box = new VBox(6);
        for (Habit h : habits) {
            double rate = ctx.habits().completionRate(h, 30);
            HBox row = new HBox(8, Fx.shrinkable(new Label(h.name)),
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
            card.content(emptyBlock("Chưa có mục tiêu nào đang chạy.", "Đặt mục tiêu",
                    () -> main.showView(MainWindow.ViewId.HABITS)));
            return card;
        }
        VBox box = new VBox(6);
        for (var g : goals) {
            int progress = computeGoalProgress(g);
            HBox row = new HBox(8, Fx.shrinkable(new Label(g.title())), Chips.chip(progress + "%",
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
        return com.lifedesk.habits.GoalProgress.percent(g, ctx.tasks().all());
    }

    private Card learningWidget() {
        Card card = new Card("Học tập");
        LearningService ls = ctx.learning();
        long minutes = ls.minutesBetween(LocalDate.now().minusDays(6), LocalDate.now());
        VBox box = new VBox(0,
                metricRow("Thẻ đến hạn ôn", String.valueOf(ls.dueCount())),
                metricRow("Tổng số thẻ", String.valueOf(ls.cardCount())),
                metricRow("Học 7 ngày qua", (minutes / 60) + "h" + (minutes % 60) + "p"));
        var active = ls.courses().stream()
                .filter(c -> c.status() == com.lifedesk.learning.CourseStatus.IN_PROGRESS).limit(5).toList();
        for (var c : active) {
            box.getChildren().add(metricRow(c.title(), c.progressPercent() + "%"));
        }
        card.content(box);
        return card;
    }

    /** Notes widget (aurora-dashboard essence): totals + 26-week writing heatmap. */
    private Card notesWidget() {
        Card card = new Card("Ghi chú");
        var notes = ctx.notes().list();
        java.util.Map<java.time.LocalDate, Double> activity = new java.util.HashMap<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate from = today.minusWeeks(25).with(java.time.DayOfWeek.MONDAY);
        for (var n : notes) {
            java.time.LocalDate day = n.updatedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            if (!day.isBefore(from)) {
                activity.merge(day, 1.0, Double::sum);
            }
        }
        Label stats = Card.dim(notes.size() + " ghi chú");
        var heatmap = new com.lifedesk.ui.components.CalendarHeatmap();
        heatmap.setPrefHeight(150);
        var palette = ctx.themeManager().previewPalette(ctx.config().theme);
        heatmap.render(from, today, activity, palette.accent(), palette.text(), palette.border());
        heatmap.installHoverTracking(activity::get, from);
        card.content(stats, heatmap);
        return card;
    }

    private Card healthWidget() {
        Card card = new Card("Sức khỏe 30 ngày");
        Double sleep = ctx.health().averageDays(com.lifedesk.health.HealthService.HealthField.SLEEP, 30);
        Double exercise = ctx.health().averageDays(com.lifedesk.health.HealthService.HealthField.EXERCISE, 30);
        Double weight = ctx.health().averageDays(com.lifedesk.health.HealthService.HealthField.WEIGHT, 30);
        VBox box = new VBox(0,
                metricRow("Giấc ngủ", sleep == null ? "—" : String.format("%.1f giờ", sleep)),
                metricRow("Vận động", exercise == null ? "—" : Math.round(exercise) + " phút/ngày"),
                metricRow("Cân nặng TB", weight == null ? "—" : String.format("%.1f kg", weight)));
        Label disclaimer = new Label("Số liệu bạn tự ghi nhận — không phải tư vấn y tế.");
        disclaimer.getStyleClass().add("metric-note");
        disclaimer.setWrapText(true);
        VBox.setMargin(disclaimer, new javafx.geometry.Insets(8, 0, 0, 0));
        box.getChildren().add(disclaimer);
        card.content(box);
        return card;
    }

    // ------------------------------------------------------------------ widget manager

    private void showWidgetManager() {
        VBox box = new VBox(8);
        Map<String, Boolean> enabled = ctx.config().dashboardWidgets;
        List<String> order = ctx.config().dashboardWidgetOrder;
        List<String> labels = List.of("w_today", "w_overdue", "w_upcoming", "w_finance", "w_budget",
                "w_habits", "w_goals", "w_learning", "w_health", "w_notes");
        Map<String, String> names = Map.of(
                "w_today", "Việc hôm nay", "w_overdue", "Quá hạn", "w_upcoming", "Sắp tới",
                "w_finance", "Biểu đồ thu chi", "w_budget", "Ngân sách", "w_habits", "Thói quen",
                "w_goals", "Mục tiêu", "w_learning", "Học tập", "w_health", "Sức khỏe",
                "w_notes", "Ghi chú");

        List<javafx.scene.control.CheckBox> checks = new ArrayList<>();
        for (String id : order) {
            javafx.scene.control.CheckBox cb = new javafx.scene.control.CheckBox(names.getOrDefault(id, id));
            cb.setSelected(Boolean.TRUE.equals(enabled.get(id)));
            cb.setUserData(id);
            checks.add(cb);
            box.getChildren().add(cb);
        }
        Label hint = Card.dim("Tích = hiện trên dashboard, bỏ tick = ẩn. Bấm OK để áp dụng.");

        // Dialogs.newDialog() = themed + owned; the raw Alert rendered as a white
        // system dialog floating over the dark dashboard.
        javafx.scene.control.Dialog<Boolean> dialog = com.lifedesk.ui.Dialogs.newDialog();
        dialog.setTitle("Quản lý widget dashboard");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(Dialogs.SAVE,
                Dialogs.CANCEL);
        dialog.getDialogPane().setContent(new VBox(8, hint, box));
        dialog.setResultConverter(bt ->
                bt.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE ? Boolean.TRUE : null);
        dialog.showAndWait().ifPresent(ok -> {
            if (ok) {
                for (var cb : checks) {
                    enabled.put((String) cb.getUserData(), cb.isSelected());
                }
                refresh();
            }
        });
    }
}
