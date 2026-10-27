package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.health.HealthEntry;
import com.lifedesk.health.HealthService;
import com.lifedesk.health.MetricDef;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.Card;
import com.lifedesk.ui.components.IconView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
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
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM");
    private final DatePicker entryDate = new DatePicker(LocalDate.now());
    private final TextField sleepField = new TextField();
    private final TextField exerciseField = new TextField();
    private final TextField weightField = new TextField();
    private final TextField noteField = new TextField();
    private final VBox metricFields = new VBox(4);
    private final Map<String, TextField> metricInputs = new HashMap<>();
    private final ComboBox<String> trendField = new ComboBox<>();
    private final VBox chartHost = new VBox();
    private com.lifedesk.ui.components.NoteLinkPicker dayNoteLinks;

    public HealthView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "HEALTH";
    }

    @Override
    protected Node build() {
        Label title = new Label("Sức khỏe (ghi nhận thủ công)");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button manageMetrics = new Button("Quản lý chỉ số tùy chọn");
        manageMetrics.setOnAction(e -> showMetricManager());
        HBox header = new HBox(10, title, spacer, manageMetrics);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- entry form
        entryDate.setOnAction(e -> loadEntry());
        Button save = new Button("Lưu hôm nay", new IconView("save", 15));
        save.getStyleClass().add("primary-btn");
        save.setOnAction(e -> saveEntry());
        addAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN), save::fire);
        Button delete = new Button("Xóa ngày này", new IconView("trash", 15));
        delete.setOnAction(e -> deleteEntry());

        HBox formRow1 = new HBox(14,
                labeled("Ngày", entryDate),
                labeled("Giờ ngủ (0-24)", sleepField),
                labeled("Phút vận động", exerciseField),
                labeled("Cân nặng (kg)", weightField));
        formRow1.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
        entryDate.setMaxWidth(Double.MAX_VALUE);
        sleepField.setMaxWidth(Double.MAX_VALUE);
        exerciseField.setMaxWidth(Double.MAX_VALUE);
        weightField.setMaxWidth(Double.MAX_VALUE);
        HBox formRow2 = new HBox(14, labeled("Ghi chú sức khỏe", noteField), new VBox(6, save, delete));
        HBox.setHgrow(formRow2.getChildren().get(0), Priority.ALWAYS);
        noteField.setMaxWidth(Double.MAX_VALUE);
        dayNoteLinks = new com.lifedesk.ui.components.NoteLinkPicker(ctx, "health",
                "date:" + entryDate.getValue(), "Ghi chú liên quan cho ngày này (sổ tay, đơn thuốc…):");

        VBox formCard = new VBox(10, formRow1, metricFields, formRow2, dayNoteLinks);
        formCard.getStyleClass().add("card");

        // ---- trends
        trendField.getItems().addAll("Giấc ngủ", "Vận động (phút)", "Cân nặng (kg)");
        trendField.setValue("Cân nặng (kg)");
        trendField.setOnAction(e -> renderTrend());
        HBox trendHeader = new HBox(8, new Label("Xu hướng:"), trendField);
        trendHeader.setAlignment(Pos.CENTER_LEFT);
        VBox trendCard = new VBox(8, trendHeader, chartHost);
        trendCard.getStyleClass().add("card");

        Label disclaimer = new Label(HealthService.DISCLAIMER);
        disclaimer.getStyleClass().add("dim-label");
        disclaimer.setWrapText(true);

        VBox root = new VBox(12, header, formCard, trendCard, disclaimer);
        loadEntry();
        renderTrend();
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.setStyle("-fx-background-color: transparent;");
        return scroll;
    }

    private VBox labeled(String text, Node field) {
        Label l = new Label(text);
        l.getStyleClass().add("dim-label");
        if (field instanceof TextField tf) {
            tf.setPrefWidth(140);
            tf.setPromptText("—");
        }
        return new VBox(2, l, field);
    }

    private void loadEntry() {
        if (dayNoteLinks != null) {
            dayNoteLinks.setRecordId("date:" + entryDate.getValue());
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
            renderTrend();
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
            renderTrend();
        } catch (RuntimeException e) {
            Dialogs.error("Không xóa được", e.getMessage());
        }
    }

    private void renderTrend() {
        chartHost.getChildren().clear();
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(30);
        HealthService.HealthField field = switch (trendField.getValue()) {
            case "Giấc ngủ" -> HealthService.HealthField.SLEEP;
            case "Vận động (phút)" -> HealthService.HealthField.EXERCISE;
            default -> HealthService.HealthField.WEIGHT;
        };
        List<HealthService.SeriesPoint> points = ctx.health().series(from, to, field);
        if (points.isEmpty()) {
            chartHost.getChildren().add(Card.dim("Chưa có dữ liệu cho chỉ số này trong 30 ngày qua."));
            return;
        }
        NumberAxis x = new NumberAxis();
        x.setLabel("Ngày");
        x.setMinorTickVisible(false);
        x.setLowerBound(0);
        x.setUpperBound(from.until(to, java.time.temporal.ChronoUnit.DAYS));
        x.setAutoRanging(false);
        x.setTickUnit(5);
        x.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Number n) {
                return from.plusDays(Math.round(n.doubleValue())).format(DF);
            }

            @Override
            public Number fromString(String s) {
                return 0;
            }
        });
        NumberAxis y = new NumberAxis();
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setTitle(field + " — 30 ngày");
        chart.setCreateSymbols(true);
        chart.setPrefHeight(260);
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(trendField.getValue());
        long start = from.toEpochDay();
        for (HealthService.SeriesPoint p : points) {
            series.getData().add(new XYChart.Data<>(p.date().toEpochDay() - start, p.value().doubleValue()));
        }
        chart.getData().add(series);
        chartHost.getChildren().add(chart);
        // Personal target line for weight if a metric named "Cân nặng" defines one
        for (MetricDef m : ctx.health().metrics()) {
            if (m.name.toLowerCase().contains("cân") && m.target != null && field == HealthService.HealthField.WEIGHT) {
                chartHost.getChildren().add(Card.dim("Mục tiêu cá nhân: " + m.target.toPlainString()));
            }
        }
    }

    private void showMetricManager() {
        VBox list = new VBox(6);
        for (MetricDef m : ctx.health().metrics()) {
            HBox row = new HBox(8, new Label(m.name + (m.unit.isBlank() ? "" : " (" + m.unit + ")")
                    + (m.target != null ? " — mục tiêu " + m.target.toPlainString() : "")));
            Button edit = new Button("Sửa");
            edit.setOnAction(e -> {
                javafx.scene.control.Dialog<Boolean> dialog = Dialogs.newDialog();
                dialog.setTitle("Sửa chỉ số");
                dialog.setHeaderText(null);
                dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                        javafx.scene.control.ButtonType.CANCEL);
                TextField nameField = new TextField(m.name);
                TextField unitField = new TextField(m.unit);
                TextField targetField = new TextField(m.target == null ? "" : m.target.toPlainString());
                dialog.getDialogPane().setContent(new VBox(8,
                        new Label("Tên *"), nameField, new Label("Đơn vị"), unitField,
                        new Label("Mục tiêu cá nhân"), targetField));
                dialog.setResultConverter(bt -> bt.getButtonData()
                        == javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
                dialog.showAndWait().ifPresent(ok -> {
                    if (!ok) {
                        return;
                    }
                    try {
                        ctx.health().updateMetric(m.id, nameField.getText(), unitField.getText(),
                                targetField.getText().isBlank() ? null : new BigDecimal(targetField.getText().trim()));
                        loadEntry();
                    } catch (NumberFormatException ex) {
                        Dialogs.error("Mục tiêu không hợp lệ", "Nhập số hoặc để trống.");
                    } catch (RuntimeException ex) {
                        Dialogs.error("Không sửa được chỉ số", ex.getMessage());
                    }
                });
            });
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
        loadEntry();
        renderTrend();
    }

    /** A health record is addressed by its date, so deep links jump the date picker to it. */
    @Override
    public void openRecord(String recordId) {
        ctx.health().entries().stream().filter(e -> e.id.equals(recordId)).findFirst()
                .ifPresent(e -> {
                    entryDate.setValue(e.date);
                    renderTrend();
                });
    }
}
