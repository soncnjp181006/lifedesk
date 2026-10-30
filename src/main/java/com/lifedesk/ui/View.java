package com.lifedesk.ui;

import com.lifedesk.app.AppContext;
import javafx.scene.Node;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Base class for module views. Built lazily; refreshed on show. */
public abstract class View extends VBox {
    protected final AppContext ctx;
    protected final MainWindow main;
    private boolean built = false;
    private volatile boolean refreshQueued = false;

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

    /** Last service revision this view painted, per notifier. */
    private final java.util.Map<com.lifedesk.common.ChangeNotifier, Long> paintedRevision =
            new java.util.HashMap<>();

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
            refresh();
        }
    }

    /**
     * Coalesce service change notifications into one {@link #refresh()} on the
     * FX thread. Services may fire from background threads; several changes in
     * quick succession produce a single repaint.
     */
    protected void scheduleRefresh() {
        if (refreshQueued) {
            return;
        }
        refreshQueued = true;
        // A background change may arrive for data this view already considers
        // painted, so forget all stamps: the next onShow() must re-read once.
        paintedRevision.clear();
        javafx.application.Platform.runLater(() -> {
            refreshQueued = false;
            refresh();
        });
    }

    /** Persist app config on the IO executor after a UI-initiated change. */
    protected void saveConfig() {
        Fx.async(ctx.executors().io(), () -> {
            new com.lifedesk.config.ConfigService(com.lifedesk.config.ConfigService.defaultConfigDir())
                    .save(ctx.config());
            ctx.persistAiSnapshot();
            return true;
        }, ok -> {
        }, err -> main.status("Lưu cấu hình thất bại: " + err.getMessage()));
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
