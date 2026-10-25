package com.lifedesk.ui.components;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Empty / loading / error state placeholder. */
public class EmptyState extends VBox {

    public EmptyState(String icon, String message, Node... actions) {
        getStyleClass().add("empty-state");
        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().add("icon");
        Label msg = new Label(message);
        msg.getStyleClass().add("msg");
        msg.setWrapText(true);
        getChildren().addAll(iconLabel, msg);
        for (Node a : actions) {
            if (a instanceof Button b) {
                b.getStyleClass().add("primary-btn");
            }
            getChildren().add(a);
        }
    }

    public static EmptyState loading(String message) {
        var box = new EmptyState("⏳", message);
        var progress = new javafx.scene.control.ProgressIndicator();
        box.getChildren().add(1, progress);
        return box;
    }

    public static EmptyState error(String message) {
        return new EmptyState("⚠️", message);
    }
}
