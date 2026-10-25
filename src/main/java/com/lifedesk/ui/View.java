package com.lifedesk.ui;

import com.lifedesk.app.AppContext;
import javafx.scene.Node;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Base class for module views. Built lazily; refreshed on show. */
public abstract class View extends VBox {
    protected final AppContext ctx;
    protected final MainWindow main;
    private boolean built = false;

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

    void ensureBuilt() {
        if (!built) {
            Node n = build();
            getChildren().add(n);
            VBox.setVgrow(n, Priority.ALWAYS);
            built = true;
        }
    }
}
