package com.lifedesk.ui.components;

import javafx.geometry.Pos;
import com.lifedesk.ui.components.Card;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Two-column form: a fixed label column so every field starts on the same x,
 * plus an optional trailing action column. Hand-rolled HBox rows drift because
 * each label has its own width.
 */
public class FormGrid extends GridPane {

    private static final double LABEL_WIDTH = 150;

    public FormGrid() {
        setHgap(12);
        setVgap(10);
        setAlignment(Pos.CENTER_LEFT);
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(LABEL_WIDTH);
        label.setPrefWidth(LABEL_WIDTH);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        field.setFillWidth(true);
        ColumnConstraints action = new ColumnConstraints();
        action.setMinWidth(Region.USE_PREF_SIZE);
        getColumnConstraints().addAll(label, field, action);
    }

    public FormGrid row(String labelText, Node field) {
        return row(labelText, field, null);
    }

    public FormGrid row(String labelText, Node field, Node action) {
        int rowIndex = getRowCount();
        Label label = new Label(labelText);
        label.getStyleClass().add("form-label");
        label.setWrapText(true);
        GridPane.setValignment(label, javafx.geometry.VPos.CENTER);
        if (field instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        add(label, 0, rowIndex);
        add(field, 1, rowIndex);
        if (action != null) {
            add(action, 2, rowIndex);
        }
        return this;
    }

    /**
     * A dim-labeled field in its own VBox — the standard layout for compact
     * dialog forms that do not use the aligned two-column grid.
     */
    public static VBox labeled(String labelText, Node field) {
        Label l = Card.dim(labelText);
        return new VBox(2, l, field);
    }
}
