package com.lifedesk.ui.components;

import javafx.geometry.NodeOrientation;
import javafx.scene.Group;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A vector icon loaded from {@code /icons/<name>.svg} (Lucide geometry, 24x24 viewBox).
 *
 * <p>The SVG file carries geometry only. Colour, stroke weight and caps come from the
 * {@code .ld-icon-part} rule in base.css, so an icon follows the active palette exactly
 * like every other control — Java code never names a color.
 */
public final class IconView extends StackPane {
    private static final Logger log = LoggerFactory.getLogger(IconView.class);
    private static final double CANVAS = 24;
    private static final Map<String, List<Shape>> CACHE = new ConcurrentHashMap<>();
    private static final Pattern ELEMENT = Pattern.compile("<(path|circle|ellipse|rect|line|polyline|polygon)\\b([^>]*?)/>");
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

    public IconView(String name) {
        this(name, 16);
    }

    public IconView(String name, double size) {
        this(name, size, new String[0]);
    }

    public IconView(String name, double size, String... extraClasses) {
        Group art = new Group();
        for (Shape part : shapesFor(name)) {
            art.getChildren().add(part);
        }
        art.setScaleX(size / CANVAS);
        art.setScaleY(size / CANVAS);
        getChildren().setAll(art);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setMouseTransparent(true);
        setNodeOrientation(NodeOrientation.INHERIT);
        getStyleClass().add("ld-icon");
        for (String cls : extraClasses) {
            getStyleClass().add(cls);
        }
    }

    public IconView tooltip(String text) {
        setMouseTransparent(false);
        Tooltip.install(this, new Tooltip(text));
        return this;
    }

    /** Parsed, CSS-styled primitives for an icon name; shared templates are copied per use. */
    private static List<Shape> shapesFor(String name) {
        List<Shape> template = CACHE.computeIfAbsent(name, IconView::load);
        List<Shape> copies = new ArrayList<>(template.size());
        for (Shape part : template) {
            copies.add(copyOf(part));
        }
        return copies;
    }

    private static List<Shape> load(String name) {
        String resource = "/icons/" + name + ".svg";
        try (InputStream in = IconView.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.warn("Thiếu icon: {}", resource);
                return List.of();
            }
            String svg = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<Shape> shapes = new ArrayList<>();
            Matcher element = ELEMENT.matcher(svg);
            while (element.find()) {
                Shape shape = toShape(element.group(1), attrs(element.group(2)));
                if (shape != null) {
                    shape.getStyleClass().add("ld-icon-part");
                    shapes.add(shape);
                }
            }
            return shapes;
        } catch (IOException e) {
            log.warn("Không đọc được icon {}: {}", resource, e.toString());
            return List.of();
        }
    }

    private static Map<String, String> attrs(String raw) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        Matcher a = ATTR.matcher(raw);
        while (a.find()) {
            map.put(a.group(1), a.group(2));
        }
        return map;
    }

    private static double num(Map<String, String> attrs, String key) {
        String v = attrs.get(key);
        return v == null ? 0 : Double.parseDouble(v.trim());
    }

    private static Shape toShape(String tag, Map<String, String> a) {
        switch (tag) {
            case "path" -> {
                SVGPath p = new SVGPath();
                p.setContent(a.getOrDefault("d", ""));
                return p;
            }
            case "circle" -> {
                return new Ellipse(num(a, "cx"), num(a, "cy"), num(a, "r"), num(a, "r"));
            }
            case "ellipse" -> {
                return new Ellipse(num(a, "cx"), num(a, "cy"), num(a, "rx"), num(a, "ry"));
            }
            case "rect" -> {
                Rectangle r = new Rectangle(num(a, "x"), num(a, "y"), num(a, "width"), num(a, "height"));
                r.setArcWidth(num(a, "rx") * 2);
                r.setArcHeight(num(a, "ry") * 2);
                return r;
            }
            case "line" -> {
                return new Line(num(a, "x1"), num(a, "y1"), num(a, "x2"), num(a, "y2"));
            }
            case "polygon" -> {
                Polygon polygon = new Polygon();
                polygon.getPoints().addAll(pointList(a.getOrDefault("points", "")));
                return polygon;
            }
            case "polyline" -> {
                Polyline polyline = new Polyline();
                polyline.getPoints().addAll(pointList(a.getOrDefault("points", "")));
                return polyline;
            }
            default -> {
            }
        }
        return null;
    }

    private static List<Double> pointList(String raw) {
        List<Double> values = new ArrayList<>();
        for (String token : raw.trim().split("[\\s,]+")) {
            if (!token.isEmpty()) {
                values.add(Double.parseDouble(token));
            }
        }
        return values;
    }

    private static Shape copyOf(Shape source) {
        Shape copy;
        if (source instanceof SVGPath s) {
            SVGPath p = new SVGPath();
            p.setContent(s.getContent());
            copy = p;
        } else if (source instanceof Ellipse e) {
            copy = new Ellipse(e.getCenterX(), e.getCenterY(), e.getRadiusX(), e.getRadiusY());
        } else if (source instanceof Rectangle r) {
            Rectangle c = new Rectangle(r.getX(), r.getY(), r.getWidth(), r.getHeight());
            c.setArcWidth(r.getArcWidth());
            c.setArcHeight(r.getArcHeight());
            copy = c;
        } else if (source instanceof Line l) {
            copy = new Line(l.getStartX(), l.getStartY(), l.getEndX(), l.getEndY());
        } else if (source instanceof Polygon poly) {
            Polygon c = new Polygon();
            c.getPoints().addAll(poly.getPoints());
            copy = c;
        } else if (source instanceof Polyline line) {
            Polyline c = new Polyline();
            c.getPoints().addAll(line.getPoints());
            copy = c;
        } else {
            throw new IllegalStateException("Unsupported icon primitive: " + source.getClass());
        }
        copy.getStyleClass().addAll(source.getStyleClass());
        copy.setFill(Color.TRANSPARENT);
        return copy;
    }
}
