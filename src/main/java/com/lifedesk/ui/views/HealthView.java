package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.health.HealthEntry;
import com.lifedesk.health.HealthService;
import com.lifedesk.health.MetricDef;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.Card;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.FormGrid;
import com.lifedesk.ui.components.IconView;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Health: manual daily log (sleep, exercise, weight + custom metrics),
 * personal targets and trend charts. Display-only analysis; a permanent
 * disclaimer states the app never gives medical advice.
 */
public class HealthView extends View {
    private static final DateTimeFormatter DF_FULL = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private final DatePicker entryDate = new DatePicker(LocalDate.now());
    private final TextField sleepField = new TextField();
    private final TextField exerciseField = new TextField();
    private final TextField weightField = new TextField();
    private final TextField noteField = new TextField();
    private final VBox metricFields = new VBox(4);
    private final Map<String, TextField> metricInputs = new HashMap<>();
    private final TableView<HealthEntry> historyTable = new TableView<>();
    private final VBox sleepChartHost = new VBox();
    private final VBox weightChartHost = new VBox();
    private final com.lifedesk.ui.components.ActivityRing sleepRing =
            new com.lifedesk.ui.components.ActivityRing("Ngủ", "#5b8def");
    private final com.lifedesk.ui.components.ActivityRing exerciseRing =
            new com.lifedesk.ui.components.ActivityRing("Vận động", "#4caf7d");
    private final com.lifedesk.ui.components.ActivityRing logRing =
            new com.lifedesk.ui.components.ActivityRing("Ghi nhận 30 ngày", "#e0a13d");
    private final com.lifedesk.ui.components.CalendarHeatmap heatmap =
            new com.lifedesk.ui.components.CalendarHeatmap();
    private com.lifedesk.ui.components.NoteLinkPicker dayNoteLinks;
    private javafx.scene.control.TitledPane noteLinksPane;

    public HealthView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "HEALTH";
    }

    @Override
    protected Node build() {
        Label title = new Label("Sức khỏe");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button manageMetrics = new Button("Chỉ số tùy chọn…");
        manageMetrics.setTooltip(new javafx.scene.control.Tooltip("Thêm/bỏ chỉ số bạn tự theo dõi"));
        manageMetrics.setOnAction(e -> showMetricManager());
        HBox header = new HBox(10, title, spacer, manageMetrics);
        header.getStyleClass().add("view-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- vitals: today's rings, one caption line per ring (inside)
        HBox rings = new HBox(40, sleepRing, exerciseRing, logRing);
        rings.setAlignment(Pos.CENTER);
        Card vitalsCard = new Card("Hôm nay");
        vitalsCard.content(rings);

        // ---- charts always visible: sleep | weight side by side, no mode switcher
        Card sleepCard = new Card("Giấc ngủ — 30 ngày");
        sleepCard.content(sleepChartHost);
        Card weightCard = new Card("Cân nặng — 30 ngày");
        weightCard.content(weightChartHost);
        HBox chartsRow = new HBox(12, sleepCard, weightCard);
        chartsRow.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        Card heatmapCard = new Card("Tần suất vận động — 26 tuần gần nhất");
        heatmapCard.content(heatmap);

        // ---- entry: one compact strip, prompt text carries the labels
        entryDate.setPrefWidth(150);
        sleepField.setPromptText("Giờ ngủ (0-24)");
        exerciseField.setPromptText("Phút vận động");
        weightField.setPromptText("Cân nặng (kg)");
        noteField.setPromptText("Ghi chú sức khỏe…");
        entryDate.setOnAction(e -> {
            loadEntry();
            refreshHistory();
        });
        Button save = new Button("Lưu", new IconView("save", 15));
        save.getStyleClass().add("primary-btn");
        save.setOnAction(e -> saveEntry());
        addAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), save::fire);
        Button delete = new Button("", new IconView("trash", 15));
        delete.getStyleClass().add("icon-btn");
        delete.setTooltip(new javafx.scene.control.Tooltip("Xóa dữ liệu ngày đang chọn"));
        delete.setOnAction(e -> deleteEntry());
        sleepField.setMaxWidth(Double.MAX_VALUE);
        exerciseField.setMaxWidth(Double.MAX_VALUE);
        weightField.setMaxWidth(Double.MAX_VALUE);
        noteField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(sleepField, Priority.ALWAYS);
        HBox.setHgrow(exerciseField, Priority.ALWAYS);
        HBox.setHgrow(weightField, Priority.ALWAYS);
        HBox.setHgrow(noteField, Priority.ALWAYS);
        HBox entryRow = new HBox(8, entryDate, sleepField, exerciseField, weightField, noteField, save, delete);
        entryRow.setAlignment(Pos.CENTER_LEFT);

        dayNoteLinks = new com.lifedesk.ui.components.NoteLinkPicker(ctx, "health",
                "date:" + entryDate.getValue(), "Sổ tay, đơn thuốc, kết quả khám…");
        noteLinksPane = new javafx.scene.control.TitledPane("Gắn ghi chú cho ngày này", dayNoteLinks);
        noteLinksPane.setExpanded(false);

        VBox entryCard = new VBox(10, entryRow, metricFields, noteLinksPane);
        entryCard.getStyleClass().add("card");

        // ---- history
        buildHistoryTable();
        historyTable.setPrefHeight(150);
        Card historyCard = new Card("Lịch sử");
        historyCard.content(historyTable);

        Label disclaimer = new Label(HealthService.DISCLAIMER);
        disclaimer.getStyleClass().add("dim-label");
        disclaimer.setWrapText(true);

        VBox root = new VBox(12, header, vitalsCard, chartsRow, heatmapCard, entryCard, historyCard, disclaimer);
        loadEntry();
        refreshHistory();
        renderCharts();
        renderRings();
        return root;
    }

    /** Compact metric fields; structure shared via FormGrid. */
    private VBox labeled(String text, Node field) {
        if (field instanceof TextField tf) {
            tf.setPrefWidth(140);
        }
        return FormGrid.labeled(text, field);
    }

    private void loadEntry() {
        if (dayNoteLinks != null) {
            dayNoteLinks.setRecordId("date:" + entryDate.getValue());
        }
        if (noteLinksPane != null) {
            // Only open the pane when the day actually carries linked notes.
            noteLinksPane.setExpanded(!ctx.notes()
                    .notesLinkedTo("health", "date:" + entryDate.getValue()).isEmpty());
        }
        metricInputs.clear();
        metricFields.getChildren().clear();
        var metrics = ctx.health().metrics();
        if (!metrics.isEmpty()) {
            HBox row = new HBox(10);
            for (MetricDef m : metrics) {
                TextField tf = new TextField();
                tf.setPrefWidth(120);
                tf.setPromptText(m.unit == null || m.unit.isBlank() ? "giá trị" : m.unit);
                metricInputs.put(m.id, tf);
                row.getChildren().add(labeled(m.name + (m.target != null ? " (mục tiêu " + m.target.toPlainString() + ")" : ""), tf));
            }
            metricFields.getChildren().addAll(new Label("Chỉ số tùy chọn:"), row);
        }
        HealthEntry entry = ctx.health().byDate(entryDate.getValue()).orElse(null);
        sleepField.setText(entry != null && entry.sleepHours != null ? entry.sleepHours.toPlainString() : "");
        exerciseField.setText(entry != null && entry.exerciseMinutes != null ? String.valueOf(entry.exerciseMinutes) : "");
        weightField.setText(entry != null && entry.weightKg != null ? entry.weightKg.toPlainString() : "");
        noteField.setText(entry == null ? "" : entry.note);
        if (entry != null) {
            for (Map.Entry<String, BigDecimal> e : entry.customMetrics.entrySet()) {
                TextField tf = metricInputs.get(e.getKey());
                if (tf != null) {
                    tf.setText(e.getValue().toPlainString());
                }
            }
        }
    }

    private void saveEntry() {
        try {
            BigDecimal sleep = sleepField.getText().isBlank() ? null : new BigDecimal(sleepField.getText().trim());
            Integer exercise = exerciseField.getText().isBlank() ? null : Integer.parseInt(exerciseField.getText().trim());
            BigDecimal weight = weightField.getText().isBlank() ? null : new BigDecimal(weightField.getText().trim());
            Map<String, BigDecimal> custom = new HashMap<>();
            for (Map.Entry<String, TextField> e : metricInputs.entrySet()) {
                String v = e.getValue().getText().trim();
                if (!v.isBlank()) {
                    custom.put(e.getKey(), new BigDecimal(v));
                }
            }
            ctx.health().upsert(entryDate.getValue(), sleep, exercise, weight, custom, noteField.getText());
            refreshHistory();
            renderCharts();
            renderRings();
        } catch (NumberFormatException e) {
            Dialogs.error("Giá trị không hợp lệ", "Chỉ nhập số cho các trường số liệu.");
        } catch (RuntimeException e) {
            Dialogs.error("Không lưu được", e.getMessage());
        }
    }

    private void deleteEntry() {
        try {
            ctx.health().deleteEntry(entryDate.getValue());
            loadEntry();
            refreshHistory();
            renderCharts();
        } catch (RuntimeException e) {
            Dialogs.error("Không xóa được", e.getMessage());
        }
    }

    private void buildHistoryTable() {
        TableColumn<HealthEntry, String> dateCol = new TableColumn<>("Ngày");
        dateCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().date.format(DF_FULL)));
        TableColumn<HealthEntry, String> sleepCol = new TableColumn<>("Giờ ngủ");
        sleepCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().sleepHours == null ? "—" : p.getValue().sleepHours.toPlainString()));
        TableColumn<HealthEntry, String> exerciseCol = new TableColumn<>("Vận động (phút)");
        exerciseCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().exerciseMinutes == null ? "—" : String.valueOf(p.getValue().exerciseMinutes)));
        TableColumn<HealthEntry, String> weightCol = new TableColumn<>("Cân nặng (kg)");
        weightCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().weightKg == null ? "—" : p.getValue().weightKg.toPlainString()));
        TableColumn<HealthEntry, String> noteCol = new TableColumn<>("Ghi chú");
        noteCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().note == null ? "" : p.getValue().note));
        historyTable.getColumns().setAll(List.of(dateCol, sleepCol, exerciseCol, weightCol, noteCol));
        historyTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        historyTable.setPlaceholder(new EmptyState("heart-pulse",
                "Chưa ghi ngày nào. Nhập số liệu rồi bấm \"Lưu hôm nay\"."));
        historyTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && !b.date.equals(entryDate.getValue())) {
                entryDate.setValue(b.date);
                loadEntry();
            }
        });
    }

    private void refreshHistory() {
        var entries = ctx.health().entries().stream()
                .sorted(java.util.Comparator.comparing((HealthEntry e) -> e.date).reversed())
                .toList();
        historyTable.getItems().setAll(entries);
        entries.stream().filter(e -> e.date.equals(entryDate.getValue())).findFirst()
                .ifPresent(historyTable.getSelectionModel()::select);
    }

    /** Both trend charts at once — no mode switcher, the user sees everything. */
    private void renderCharts() {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        renderLineChart(sleepChartHost, ctx.health().series(from, to, HealthService.HealthField.SLEEP),
                from, to, "Ngủ (h)", 8.0, "Mục tiêu 8h");
        Double weightGoal = ctx.health().metrics().stream()
                .filter(m -> m.name.toLowerCase().contains("cân") && m.target != null)
                .map(m -> m.target.doubleValue()).findFirst().orElse(null);
        renderLineChart(weightChartHost, ctx.health().series(from, to, HealthService.HealthField.WEIGHT),
                from, to, "Cân nặng (kg)", weightGoal, weightGoal == null ? null : "Mục tiêu");
    }

    /** One themed line chart; a non-null goal renders as a flat reference line. */
    private void renderLineChart(VBox host, List<HealthService.SeriesPoint> points,
                                 LocalDate from, LocalDate to, String seriesName,
                                 Double goal, String goalName) {
        host.getChildren().clear();
        if (points.isEmpty() && goal == null) {
            host.getChildren().add(Card.dim("Chưa có dữ liệu trong 30 ngày qua."));
            return;
        }
        NumberAxis x = com.lifedesk.ui.components.DateAxes.epochDay("Ngày", from, to, 5);
        NumberAxis y = new NumberAxis();
        // Weight ~65kg would sit glued to the top of a 0-based axis; range to the data.
        y.setForceZeroInRange(false);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setLegendVisible(goal != null);
        chart.setCreateSymbols(true);
        chart.setPrefHeight(240);
        chart.setMaxWidth(Double.MAX_VALUE);
        long start = from.toEpochDay();
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(seriesName);
        for (HealthService.SeriesPoint p : points) {
            series.getData().add(new XYChart.Data<>(p.date().toEpochDay() - start, p.value().doubleValue()));
        }
        chart.getData().add(series);
        if (goal != null) {
            XYChart.Series<Number, Number> goalSeries = new XYChart.Series<>();
            goalSeries.setName(goalName);
            goalSeries.getData().add(new XYChart.Data<>(0, goal));
            goalSeries.getData().add(new XYChart.Data<>(to.toEpochDay() - start, goal));
            chart.getData().add(goalSeries);
        }
        host.getChildren().add(chart);
    }

    /** Rings + heatmap recompute from entries (Apple-Health-style daily closure). */
    private void renderRings() {
        LocalDate today = LocalDate.now();
        var entryOpt = ctx.health().byDate(today);
        Double sleep = entryOpt.map(e -> e.sleepHours).map(BigDecimal::doubleValue).orElse(null);
        Integer exercise = entryOpt.map(e -> e.exerciseMinutes).orElse(null);
        sleepRing.update(sleep == null ? 0 : sleep / 8.0,
                sleep == null ? "—" : String.format(java.util.Locale.ROOT, "%.1fh", sleep), "mục tiêu 8h");
        exerciseRing.update(exercise == null ? 0 : exercise / 30.0,
                exercise == null ? "—" : exercise + "'", "mục tiêu 30'");

        LocalDate from = today.minusDays(29);
        var entries = ctx.health().entries();
        long logged = entries.stream().filter(e -> !e.date.isBefore(from) && !e.date.isAfter(today)).count();
        logRing.update(logged / 30.0, logged + "/30", "ngày đã ghi");

        // 26-week exercise heatmap (missing days stay dark).
        LocalDate hmFrom = today.minusWeeks(25).with(java.time.DayOfWeek.MONDAY);
        java.util.Map<LocalDate, Double> values = new java.util.HashMap<>();
        for (HealthEntry e : entries) {
            if (!e.date.isBefore(hmFrom) && e.exerciseMinutes != null) {
                values.put(e.date, (double) e.exerciseMinutes);
            }
        }
        heatmap.render(hmFrom, today, values, "#4caf7d");
        heatmap.installHoverTracking(values::get, hmFrom);
    }

    private void showMetricManager() {
        VBox list = new VBox(6);
        for (MetricDef m : ctx.health().metrics()) {
            HBox row = new HBox(8, new Label(m.name + (m.unit.isBlank() ? "" : " (" + m.unit + ")")
                    + (m.target != null ? " — mục tiêu " + m.target.toPlainString() : "")));
            Button del = new Button("Xóa");
            del.setOnAction(e -> {
                ctx.health().deleteMetric(m.id);
                list.getChildren().remove(row);
                loadEntry();
            });
            row.getChildren().add(del);
            list.getChildren().add(row);
        }
        javafx.scene.control.Dialog<MetricDef> dialog = Dialogs.newDialog();
        dialog.setTitle("Chỉ số sức khỏe tùy chọn");
        dialog.setHeaderText("Thêm chỉ số bạn muốn theo dõi (tự nguyện, không mang tính y tế)");
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        TextField name = new TextField();
        name.setPromptText("VD: Huyết áp tâm thu");
        TextField unit = new TextField();
        unit.setPromptText("VD: mmHg");
        TextField target = new TextField();
        target.setPromptText("tùy chọn, VD 120");
        dialog.getDialogPane().setContent(new VBox(8, list, new Label("Tên *"), name, new Label("Đơn vị"), unit, new Label("Mục tiêu cá nhân"), target));
        dialog.setResultConverter(bt -> bt.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE
                && !name.getText().isBlank() ? new MetricDef() : null);
        dialog.showAndWait().ifPresent(def -> {
            try {
                ctx.health().addMetric(name.getText(), unit.getText(),
                        target.getText().isBlank() ? null : new BigDecimal(target.getText().trim()));
            } catch (RuntimeException e) {
                Dialogs.error("Không thêm được chỉ số", e.getMessage());
            }
            loadEntry();
        });
    }

    @Override
    public void onShow() {
        // Only re-read when health data changed since the last paint.
        refreshWhenChanged(ctx.health().changes());
    }

    @Override
    protected void refresh() {
        loadEntry();
        refreshHistory();
        renderCharts();
        renderRings();
    }

    /** A health record is addressed by its date, so deep links jump the date picker to it. */
    @Override
    public void openRecord(String recordId) {
        ctx.health().entries().stream().filter(e -> e.id.equals(recordId)).findFirst()
                .ifPresent(e -> {
                    entryDate.setValue(e.date);
                    loadEntry();
                    refreshHistory();
                    renderCharts();
                });
    }
}
