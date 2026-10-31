package com.lifedesk.ui.components;

import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.util.Callback;

import java.util.function.Function;

/**
 * ComboBoxes whose visible text is a friendly label while the selected value stays
 * the raw item (an enum constant, a note, a goal title).
 */
public final class Combos {
    private Combos() {
    }

    /**
     * Render {@code label(item)} in the dropdown AND in the closed button.
     *
     * <p>Six call sites hand-rolled this pair and three of them styled only the
     * dropdown, which is why a combo could show "Đang làm" in the list and then keep
     * "PLANNED" — or the default {@code toString()} of a record — on its face after
     * the user picked something. One implementation cannot forget the second half.
     */
    public static <T> void label(ComboBox<T> combo, Function<T, String> label) {
        Callback<ListView<T>, ListCell<T>> factory = v -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label.apply(item));
            }
        };
        combo.setCellFactory(factory);
        combo.setButtonCell(factory.call(null));
    }
}
