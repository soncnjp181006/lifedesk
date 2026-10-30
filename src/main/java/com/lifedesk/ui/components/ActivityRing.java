package com.lifedesk.ui.components;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Apple-Health-style progress ring on a JavaFX Canvas: a rounded track with
 * an arc for {@code progress} (0..1, clamped) and a two-line center caption
 * (value over goal). HiDPI-safe by sizing from the layout bounds.
 */
public class ActivityRing extends StackPane {
    private final Canvas canvas = new Canvas(110, 110);
    private double progress;
    private String colorClass = "#4caf7d";
    private String valueText = "";
    private String goalText = "";

    public ActivityRing(String title, String colorClass) {
        this.colorClass = colorClass;
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dim-label");
        // Value + goal live INSIDE the ring; a second goal line below just
        // duplicated the center text.
        VBox box = new VBox(4, titleLabel, canvas);
        box.setAlignment(Pos.CENTER);
        getChildren().add(box);
        setAlignment(Pos.CENTER);
        draw();
    }

    /** Set completion (0..1) and the two center lines. */
    public void update(double progress, String valueText, String goalText) {
        this.progress = Math.max(0, Math.min(1.2, progress));
        this.valueText = valueText;
        this.goalText = goalText;
        draw();
    }

    private void draw() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        double cx = w / 2;
        double cy = h / 2;
        double radius = Math.min(w, h) / 2 - 10;
        double stroke = 9;

        Color track = Color.web("#888888", 0.18);
        Color fill = Color.web(colorClass);

        g.setLineWidth(stroke);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        g.setStroke(track);
        g.strokeArc(cx - radius, cy - radius, radius * 2, radius * 2, 0, 360,
                javafx.scene.shape.ArcType.OPEN);

        double clamped = Math.min(1, Math.max(0, progress));
        if (clamped > 0.001) {
            g.setStroke(fill);
            // Start at 12 o'clock (90°) and sweep clockwise.
            g.strokeArc(cx - radius, cy - radius, radius * 2, radius * 2, 90, -360 * clamped,
                    javafx.scene.shape.ArcType.OPEN);
        }

        // Center text: value big, goal small.
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(Color.web(fxThemeText()));
        g.setFont(Font.font("System", FontWeight.BOLD, 15));
        g.fillText(valueText, cx, cy - 1);
        g.setFont(Font.font("System", 10));
        g.setFill(Color.web(fxThemeText(), 0.6));
        g.fillText(goalText, cx, cy + 14);
    }

    /** Derive readable text color from the JavaFX theme class on this node. */
    private String fxThemeText() {
        javafx.scene.Scene scene = getScene();
        if (scene != null) {
            for (String style : scene.getStylesheets()) {
                if (style.contains("light")) {
                    return "#222222";
                }
            }
        }
        return "#e8e8e8";
    }
}
