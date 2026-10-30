package com.lifedesk.ui.components;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Mindmap renderer (Canvas-MindMap essence) over a markdown outline: heading
 * levels become branches laid out left→right with rounded node boxes and
 * curved edges. Render-only visualization — the outline in the note stays the
 * source of truth.
 */
public class MindMapCanvas extends ScrollPane {
    private static final int LEVEL_WIDTH = 190;
    private static final int ROW_HEIGHT = 30;
    private static final int NODE_PAD_X = 8;
    private static final int NODE_H = 22;

    private record Node(String text, int depth, int x, int y, int w) {
    }

    private final Canvas canvas = new Canvas(400, 400);
    private final List<com.lifedesk.ui.panes.NoteOutlinePane.Heading> headings;

    public MindMapCanvas(String markdownText, String accentColor) {
        this.headings = com.lifedesk.ui.panes.NoteOutlinePane.parse(markdownText);
        layoutAndDraw(accentColor == null || accentColor.isBlank() ? "#5b8def" : accentColor);
        VBox box = new VBox(canvas);
        setContent(box);
        setFitToWidth(false);
        setPannable(true);
    }

    private void layoutAndDraw(String accent) {
        // Depth-first: assign each heading a row (y) in document order; x by depth.
        java.util.List<Node> nodes = new java.util.ArrayList<>();
        int y = 20;
        int maxDepth = 1;
        for (var h : headings) {
            int x = 20 + (h.level() - 1) * LEVEL_WIDTH;
            int w = (int) Math.max(60, measure(h.text()) + NODE_PAD_X * 2);
            nodes.add(new Node(h.text(), h.level(), x, y, w));
            y += ROW_HEIGHT;
            maxDepth = Math.max(maxDepth, h.level());
        }
        canvas.setWidth(40 + maxDepth * LEVEL_WIDTH + 120);
        canvas.setHeight(Math.max(400, y + 20));

        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        Color edge = Color.web(accent, 0.65);

        // Edges: connect each node to its nearest previous shallower node (stack logic).
        g.setStroke(edge);
        g.setLineWidth(1.6);
        ArrayDeque<Node> stack = new ArrayDeque<>();
        for (Node n : nodes) {
            while (!stack.isEmpty() && stack.peek().depth() >= n.depth()) {
                stack.pop();
            }
            if (!stack.isEmpty()) {
                Node parent = stack.peek();
                double x1 = parent.x() + parent.w();
                double y1 = parent.y() + NODE_H / 2.0;
                double x2 = n.x();
                double y2 = n.y() + NODE_H / 2.0;
                double midX = (x1 + x2) / 2;
                g.beginPath();
                g.moveTo(x1, y1);
                g.bezierCurveTo(midX, y1, midX, y2, x2, y2);
                g.stroke();
            }
            stack.push(n);
        }

        // Nodes.
        g.setFont(Font.font("System", 12));
        for (Node n : nodes) {
            double depth = (n.depth() - 1);
            g.setFill(Color.web(accent, Math.max(0.15, 0.55 - depth * 0.12)));
            g.fillRoundRect(n.x(), n.y(), n.w(), NODE_H, 10, 10);
            g.setFill(Color.web("#1d1d1d", 0.9));
            g.fillText(clip(n.text()), n.x() + NODE_PAD_X, n.y() + NODE_H - 7);
        }
    }

    private static double measure(String text) {
        javafx.scene.text.Text t = new javafx.scene.text.Text(text);
        t.setFont(Font.font("System", 12));
        return Math.min(160, t.getLayoutBounds().getWidth());
    }

    private static String clip(String s) {
        return s.length() > 24 ? s.substring(0, 23) + "…" : s;
    }
}
