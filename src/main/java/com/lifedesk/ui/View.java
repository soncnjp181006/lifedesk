package com.lifedesk.ui;

import com.lifedesk.app.AppContext;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Base class for module views. Built lazily; refreshed on show. */
public abstract class View extends VBox {
    protected final AppContext ctx;
    protected final MainWindow main;
    private boolean built = false;
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued =
            new java.util.concurrent.atomic.AtomicBoolean();

    protected View(AppContext ctx, MainWindow main) {
        this.ctx = ctx;
        this.main = main;
        setSpacing(10);
        VBox.setVgrow(this, Priority.ALWAYS);
    }

    /** Stable view id, matches MainWindow.ViewId names. */
    public abstract String id();

    /** One-time UI construction. */
    protected abstract Node build();

    /**
     * The page's action row: an optional live line on the left (a running count, a
     * filter, a query field) and the controls on the right.
     *
     * <p>There is deliberately no title. The rail already names the module, so
     * painting "Học tập" again above the page said the same thing twice and spent a
     * line of height and a line of width that the data could use. The name still
     * travels with the page where it is not decoration — the window title and the
     * content area's accessible text, both set by the shell from that one rail label.
     *
     * @param info the left cell, or null when the page has nothing live to say
     */
    protected HBox toolbar(Node info, Node... actions) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_RIGHT);
        if (info != null) {
            if (info instanceof Label l) {
                l.getStyleClass().add("page-subtitle");
                l.setWrapText(true);
            }
            if (info instanceof Region r) {
                r.setMinWidth(0);
                r.setMaxWidth(Double.MAX_VALUE);
            }
            HBox.setHgrow(info, Priority.ALWAYS);
            row.getChildren().add(info);
        }
        row.getChildren().addAll(actions);
        // A squeezed toolbar used to cut "Thêm mục" down to "Thêm m…" — a control that
        // lost its label lost its meaning. The left line is the skimmable part, so it
        // is the one that gives way.
        for (Node a : actions) {
            if (a instanceof Region r) {
                r.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        return row;
    }

    /**
     * The page body: the sections stacked on one rhythm. Views used to write this VBox
     * themselves and choose either 10, 12 or 14 for the gap between the header and what
     * it headed, so the same distance looked different from screen to screen.
     */
    protected VBox page(Node... sections) {
        VBox box = new VBox(12);
        box.getChildren().addAll(sections);
        return box;
    }

    /** Called every time the view becomes visible. */
    public void onShow() {
    }

    public void onHide() {
    }

    /** Open a specific record (from search results or notifications). */
    public void openRecord(String recordId) {
    }

    /**
     * Called after the theme stylesheet swap. JavaFX nodes recolor themselves via
     * the new stylesheet, but WebView content (markdown preview, review cards,
     * charts) carries its own palette inside the HTML — subclasses re-render here
     * or those panes keep the previous theme's colors.
     */
    public void onThemeChanged() {
    }

    /**
     * Re-read data and update the UI. Called by {@link #onShow()} overrides and
     * by {@link #scheduleRefresh()} when a service fires a background change.
     */
    protected void refresh() {
    }

    /**
     * Last service revision this view painted, per notifier. Written from a
     * service thread by {@link #scheduleRefresh()} and read on the FX thread by
     * {@link #refreshWhenChanged}, so it is a concurrent map: a plain HashMap
     * cleared underneath an iteration on the other thread can spin forever.
     */
    private final java.util.Map<com.lifedesk.common.ChangeNotifier, Long> paintedRevision =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Repaints performed through the two shell hooks (show + service change).
     * The smoke gate reads it to prove a hidden view stays put; a view that
     * repaints from its own button handler does not go through here.
     */
    private final java.util.concurrent.atomic.AtomicInteger paints =
            new java.util.concurrent.atomic.AtomicInteger();

    public int paintCount() {
        return paints.get();
    }

    /**
     * Subscribe to one service. Every data view wrote this same line by hand next
     * to its own {@link #refreshWhenChanged} call, and two of them also painted
     * inside build() — so their very first visit rebuilt the page twice.
     */
    protected void watch(com.lifedesk.common.ChangeNotifier... notifiers) {
        for (var n : notifiers) {
            n.addListener(this::scheduleRefresh);
        }
    }

    /**
     * Repaint on show only if the data behind this view actually changed since the
     * last paint. Views that are shown repeatedly (every module in a session) used
     * to re-read, re-filter and re-fill their whole model on every visit; skipping
     * that when nothing changed is the difference between a switch that lands in one
     * frame and one that spends several milliseconds rebuilding identical rows.
     */
    protected void refreshWhenChanged(com.lifedesk.common.ChangeNotifier... notifiers) {
        boolean changed = false;
        for (var n : notifiers) {
            Long painted = paintedRevision.get(n);
            long now = n.revision();
            if (painted == null || painted != now) {
                paintedRevision.put(n, now);
                changed = true;
            }
        }
        if (changed) {
            paints.incrementAndGet();
            refresh();
        }
    }

    /**
     * Coalesce service change notifications into one {@link #refresh()} on the
     * FX thread. Services may fire from background threads; several changes in
     * quick succession produce a single repaint.
     */
    protected void scheduleRefresh() {
        // A background change may arrive for data this view already considers
        // painted, so forget all stamps: the next onShow() must re-read once.
        paintedRevision.clear();
        if (!refreshQueued.compareAndSet(false, true)) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            refreshQueued.set(false);
            // The shell takes a hidden view out of the scene graph, so a view with
            // no parent is one nobody is looking at. Rebuilding it anyway spent a
            // whole frame — table re-fill, chart re-layout — on pixels that are not
            // on screen; that frame is what a switch or a click had to queue behind.
            // Skipping it costs nothing: clearing the revision stamps above already
            // guarantees onShow() re-reads the data when the view comes back.
            if (getParent() == null) {
                return;
            }
            paints.incrementAndGet();
            refresh();
        });
    }

    /**
     * Persist app config after a UI-initiated change. The shell owns the write (one
     * executor, one error path, one snapshot); every view used to copy it out.
     */
    protected void saveConfig() {
        main.saveConfig();
    }

    /** Bind a shortcut that is live only while this view is on screen. */
    protected void addAccelerator(KeyCodeCombination key, Runnable action) {
        main.addAccelerator(MainWindow.ViewId.valueOf(id()), key, action);
    }

    void ensureBuilt() {
        if (!built) {
            Node n = build();
            getChildren().add(n);
            VBox.setVgrow(n, Priority.ALWAYS);
            built = true;
        }
    }
}
