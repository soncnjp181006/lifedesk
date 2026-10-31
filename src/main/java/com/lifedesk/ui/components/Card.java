package com.lifedesk.ui.components;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Themed card container used by the dashboard and detail panes. */
public class Card extends VBox {
    private final Label titleLabel = new Label();

    public Card(String title) {
        getStyleClass().add("card");
        // .card owns -fx-spacing in CSS; setting it here only made a second number to
        // keep in step with the stylesheet.
        // Cards live in equal-width grids; without this their content width becomes
        // their minimum and the whole grid overflows the viewport.
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        titleLabel.getStyleClass().add("card-title");
        titleLabel.setText(title);
        HBox header = new HBox(titleLabel);
        header.setSpacing(6);
        getChildren().add(header);
        VBox.setVgrow(header, Priority.NEVER);
    }

    public void content(Node... nodes) {
        if (getChildren().size() > 1) {
            getChildren().remove(1, getChildren().size());
        }
        for (Node n : nodes) {
            getChildren().add(n);
        }
    }

    public static Label dim(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("dim-label");
        l.setWrapText(true);
        VBox.setMargin(l, new Insets(0, 0, 0, 0));
        return l;
    }
}
