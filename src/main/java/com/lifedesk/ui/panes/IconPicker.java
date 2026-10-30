package com.lifedesk.ui.panes;

import com.lifedesk.ui.components.IconView;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;
import java.util.function.Consumer;

/**
 * Searchable icon picker (iconic essence): click an icon slot anywhere → this
 * popup lists every built-in icon with a live filter; clicking one applies it
 * immediately. Icon names are the Lucide files under {@code /icons}.
 */
public final class IconPicker {
    /** The full built-in set (files present in src/main/resources/icons). */
    public static final List<String> ICONS = List.of(
            "bell", "book-open", "bot", "chart-column", "check", "chevron-left", "chevron-right",
            "circle-check", "circle-x", "clipboard-list", "coins", "download", "eye",
            "file-text", "flag", "flame", "flask-conical", "folder", "folder-open",
            "heart-pulse", "hourglass", "kanban", "layout-dashboard", "library-big",
            "list", "list-checks", "menu", "pencil", "plus", "puzzle", "recycle", "repeat",
            "save", "scroll-text", "search", "send", "settings", "star", "sticky-note",
            "target", "trash", "triangle-alert", "wallet");

    private IconPicker() {
    }

    /** Show the picker anchored to {@code anchor}; selected icon goes to {@code onPick}. */
    public static Popup show(javafx.scene.Node anchor, Consumer<String> onPick) {
        Popup popup = new Popup();
        popup.setAutoHide(true);

        TextField search = new TextField();
        search.setPromptText("Tìm icon…");
        FlowPane grid = new FlowPane(4, 4);
        grid.setPadding(new Insets(6));

        Runnable rebuild = () -> {
            grid.getChildren().clear();
            String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
            for (String name : ICONS) {
                if (!q.isEmpty() && !name.contains(q)) {
                    continue;
                }
                Label tile = new Label("", new IconView(name, 18));
                tile.getStyleClass().add("icon-tile");
                tile.setAlignment(Pos.CENTER);
                tile.setPrefSize(38, 38);
                tile.setTooltip(new Tooltip(name));
                tile.setOnMouseClicked(e -> {
                    onPick.accept(name);
                    popup.hide();
                });
                grid.getChildren().add(tile);
            }
            if (grid.getChildren().isEmpty()) {
                grid.getChildren().add(new Label("(không có icon khớp)"));
            }
        };
        search.textProperty().addListener((o, a, b) -> rebuild.run());
        rebuild.run();

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(260, 220);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox pane = new VBox(6, search, scroll);
        pane.setPadding(new Insets(8));
        pane.getStyleClass().add("notif-popup");
        popup.getContent().setAll(pane);
        var screen = anchor.localToScreen(0, 0);
        if (screen != null) {
            popup.show(anchor, screen.getX(), screen.getY() + anchor.getBoundsInLocal().getHeight());
        } else {
            popup.show(anchor, 100, 100);
        }
        return popup;
    }
}
