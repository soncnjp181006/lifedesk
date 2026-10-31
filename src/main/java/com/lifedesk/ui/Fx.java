package com.lifedesk.ui;

import javafx.application.Platform;

/** Helpers for running work on/off the JavaFX Application Thread. */
public final class Fx {
    private Fx() {
    }

    public static void runLater(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** Run I/O work on a background executor; handle result/error on the FX thread. */
    public static <T> void async(java.util.concurrent.Executor executor,
                                 java.util.function.Supplier<T> work,
                                 java.util.function.Consumer<T> onSuccess,
                                 java.util.function.Consumer<Throwable> onError) {
        executor.execute(() -> {
            try {
                T result = work.get();
                if (onSuccess != null) {
                    runLater(() -> onSuccess.accept(result));
                }
            } catch (Throwable t) {
                if (onError != null) {
                    runLater(() -> onError.accept(t));
                } else {
                    runLater(() -> Dialogs.error("Lỗi nền", t.getMessage() == null ? t.toString() : t.getMessage()));
                }
            }
        });
    }

    public static void async(java.util.concurrent.Executor executor,
                             Runnable work,
                             Runnable onSuccess,
                             java.util.function.Consumer<Throwable> onError) {
        async(executor, () -> {
            work.run();
            return true;
        }, ok -> {
            if (onSuccess != null) {
                onSuccess.run();
            }
        }, onError);
    }

    /**
     * Run the work after the user stops typing (250ms), instead of once per
     * keystroke. Filtering a table re-reads, re-filters and re-fills every row, and
     * two of the filter boxes also reset a ComboBox whose popup the user may have
     * open — so per-keystroke repaints made typing feel heavy and could close the
     * dropdown under the pointer. NotesView already owned this timer; the rest of
     * the app is now wired to the same idiom.
     */
    public static javafx.animation.PauseTransition debounced(
            javafx.scene.control.TextField field, Runnable onSettled) {
        javafx.animation.PauseTransition timer = new javafx.animation.PauseTransition(
                javafx.util.Duration.millis(250));
        timer.setOnFinished(e -> onSettled.run());
        field.textProperty().addListener((o, a, b) -> {
            timer.stop();
            timer.playFromStart();
        });
        return timer;
    }

    /**
     * Mark a label as the part of its row that may shorten. An HBox that runs out
     * of room takes a little off *every* child by default, which is how a status
     * chip turned into "chuỗi 0 n…": the chip is the fact, the sentence next to it
     * is the skimmable part, so the sentence is the one that should lose letters.
     */
    public static javafx.scene.control.Label shrinkable(javafx.scene.control.Label l) {
        l.setMinWidth(0);
        l.setMaxWidth(Double.MAX_VALUE);
        if (l.getTooltip() == null) {
            // A label allowed to lose letters must give them back somewhere: the full
            // text rides along on hover, kept in step with the label by a binding.
            javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip();
            tip.textProperty().bind(l.textProperty());
            l.setTooltip(tip);
        }
        return l;
    }

    /**
     * A sentence that keeps every word: it takes another line when the column gets
     * narrow instead of eliding. For a label that explains what a control does, the
     * tail is the point — "Đổi thư mục dữ liệu: đóng app, đặt thuộc…" leaves the
     * user without the instruction, and a tooltip hides the fix behind a hover.
     */
    public static javafx.scene.control.Label wrapped(String text) {
        javafx.scene.control.Label l = new javafx.scene.control.Label(text);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    /**
     * A row of equal cells that never shrinks below the widest card: a TilePane gives
     * every tile the width of the largest one and drops to a second row when they no
     * longer fit. An HBox with hgrow instead shares out only the *spare* width on top
     * of each child's own content width, so a strip of KPI cards comes out ragged, and
     * equal percent columns even them by cutting "24.500.000 đ" down to "24.500.0…".
     * Same size, every digit, or the row wraps.
     */
    public static javafx.scene.layout.Pane equalTiles(double gap, javafx.scene.Node... cells) {
        javafx.scene.layout.TilePane row = new javafx.scene.layout.TilePane();
        row.setHgap(gap);
        row.setVgap(gap);
        row.setPrefColumns(cells.length);
        for (javafx.scene.Node cell : cells) {
            if (cell instanceof javafx.scene.layout.Region r) {
                r.setMaxWidth(Double.MAX_VALUE);
            }
            row.getChildren().add(cell);
        }
        return row;
    }

    /**
     * Pin every control in a toolbar row to its natural width except the ones that
     * are allowed to give way (usually the search field).
     *
     * <p>JavaFX has no CSS way to say "min = pref", so an HBox that runs out of
     * room shrinks every child a little — which is how "Board" became "Bo…" and
     * "Thêm mục" became "Thêm m…". A control that lost its label lost its meaning;
     * the search field is the one that may get short.
     */
    public static void pinFixedWidths(javafx.scene.layout.HBox row, javafx.scene.Node... flexible) {
        for (javafx.scene.Node n : row.getChildren()) {
            boolean mayShrink = false;
            for (javafx.scene.Node f : flexible) {
                if (n == f) {
                    mayShrink = true;
                    break;
                }
            }
            if (!mayShrink && n instanceof javafx.scene.layout.Region r) {
                r.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            }
        }
    }

    /**
     * Last two segments of a path or URL: "…/lifedesk\data" instead of the whole tree.
     * A full absolute path is wider than the row it sits in, so JavaFX elides it — and
     * the surviving letters are the *drive*, which identifies nothing. The tail is what
     * a user actually reads; the complete value stays available in a tooltip.
     */
    public static String shortenPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int end = raw.length();
        while (end > 0 && isSep(raw.charAt(end - 1))) {
            end--; // ignore trailing separators: they are not a segment
        }
        int last = sepBefore(raw, end - 1);
        int prev = last < 0 ? -1 : sepBefore(raw, last - 1);
        if (prev < 0) {
            return raw; // one or two segments: already short enough to read whole
        }
        return "…/" + raw.substring(prev + 1, end);
    }

    private static boolean isSep(char c) {
        return c == '/' || c == '\\';
    }

    private static int sepBefore(String s, int from) {
        for (int i = from; i >= 0; i--) {
            if (isSep(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
