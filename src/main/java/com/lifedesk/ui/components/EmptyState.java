package com.lifedesk.ui.components;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Empty / loading / error state placeholder. The icon argument names a file in /icons. */
public class EmptyState extends VBox {

    public EmptyState(String iconName, String message, Node... actions) {
        this(new IconView(iconName, 34), message, actions);
    }

    /**
     * An empty page that names a button in prose ("Bấm \"Thêm feed\"") is making the
     * user read a sentence and then hunt for the control it describes. Offer the
     * control instead, right under the sentence.
     */
    public static EmptyState withAction(String iconName, String message,
            String actionLabel, Runnable onAction) {
        Button action = new Button(actionLabel);
        action.setOnAction(e -> onAction.run());
        return new EmptyState(iconName, message, action);
    }

    public EmptyState(IconView icon, String message, Node... actions) {
        getStyleClass().add("empty-state");
        icon.getStyleClass().add("icon");
        Label msg = new Label(message);
        msg.getStyleClass().add("msg");
        msg.setWrapText(true);
        getChildren().addAll(icon, msg);
        for (Node a : actions) {
            if (a instanceof Button b) {
                b.getStyleClass().add("primary-btn");
            }
            getChildren().add(a);
        }
    }
}
