package com.lifedesk.ui.components;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;

/**
 * GitHub-style calendar heatmap on a JavaFX Canvas — the "consistency view"
 * from health-md-visualizations. Cells are colored by intensity
 * (max-normalized); missing days stay dark, days in the future are hidden.
 */
public class CalendarHeatmap extends VBox {
    private static final int CELL = 13;
    private static final int GAP = 3;

    private final Canvas canvas = new Canvas(100, 7 * CELL);
    private String accent = "#4caf7d";

    /** weekStart (Monday-first) → per-day value in [0..1] plus raw values for tooltips. */
    public void render(LocalDate from, LocalDate to, Map<LocalDate, Double> values, String accentColor) {
        this.accent = accentColor == null || accentColor.isBlank() ? accent : accentColor;
        if (values.isEmpty() || to.isBefore(from)) {
            getChildren().setAll(new com.lifedesk.ui.components.EmptyState("flame", "Chưa có dữ liệu cho heatmap."));
            return;
        }
        // Align the start to the previous Monday.
        LocalDate start = from.with(DayOfWeek.MONDAY);
        int weeks = (int) Math.ceil(java.time.temporal.ChronoUnit.DAYS.between(start, to) / 7.0) + 1;
        double w = weeks * (CELL + GAP) + 26;
        double h = 7 * (CELL + GAP);
        canvas.setWidth(w);
        canvas.setHeight(h);

        double max = values.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        if (max <= 0) {
            max = 1;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        Color base = Color.web(accent);
        LocalDate today = LocalDate.now();
        // Month labels along the top.
        g.setFill(Color.GRAY);
        g.setFont(javafx.scene.text.Font.font("System", 9));
        int lastMonth = -1;
        for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.MONDAY && d.getMonthValue() != lastMonth) {
                lastMonth = d.getMonthValue();
                int col = (int) (d.toEpochDay() - start.toEpochDay()) / 7;
                g.fillText(d.getMonthValue() + "", 26 + col * (CELL + GAP), 8);
            }
        }
        // Weekday initials on the left.
        String[] wd = {"T2", "", "T4", "", "T6", "", "CN"};
        for (int r = 0; r < 7; r++) {
            if (!wd[r].isEmpty()) {
                g.fillText(wd[r], 2, 18 + r * (CELL + GAP) + CELL);
            }
        }

        for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1)) {
            int col = (int) (d.toEpochDay() - start.toEpochDay()) / 7;
            int row = d.getDayOfWeek().getValue() - 1;
            double x = 26 + col * (CELL + GAP);
            double y = 14 + row * (CELL + GAP);
            Double v = values.get(d);
            if (d.isAfter(today)) {
                continue; // future cells invisible
            }
            if (v == null || v <= 0) {
                g.setFill(Color.GRAY.deriveColor(0, 1, 1, 0.12));
            } else {
                double intensity = 0.25 + 0.75 * Math.min(1, v / max);
                g.setFill(Color.web(accent, intensity));
            }
            g.fillRoundRect(x, y, CELL, CELL, 3, 3);
        }
        getChildren().setAll(canvas);
    }

    /** Live tooltip for the hovered cell (single listener for the whole canvas). */
    public void installHoverTracking(java.util.function.Function<LocalDate, Double> valueLookup,
                                     LocalDate from) {
        javafx.scene.control.Label bubble = new javafx.scene.control.Label();
        bubble.getStyleClass().add("tooltip");
        bubble.setVisible(false);
        canvas.setOnMouseMoved(e -> {
            int col = (int) ((e.getX() - 26) / (CELL + GAP));
            int row = (int) ((e.getY() - 14) / (CELL + GAP));
            if (col < 0 || row < 0 || row > 6) {
                bubble.setVisible(false);
                return;
            }
            LocalDate d = from.plusDays((long) col * 7 + row);
            Double v = valueLookup.apply(d);
            if (v == null) {
                bubble.setText(d + ": —");
            } else {
                bubble.setText(d + ": " + v);
            }
            bubble.relocate(e.getX(), Math.max(0, e.getY() - 26));
            bubble.setVisible(true);
        });
        canvas.setOnMouseExited(e -> bubble.setVisible(false));
        if (!getChildren().contains(bubble)) {
            getChildren().add(bubble);
        }
    }
}
