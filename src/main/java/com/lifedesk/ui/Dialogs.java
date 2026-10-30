package com.lifedesk.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

import java.util.List;
import java.util.Optional;

/**
 * Modal dialogs with consistent styling. Every dialog gets the active theme's
 * stylesheets (JavaFX dialogs do NOT automatically inherit the owner scene's
 * stylesheets) and the focused stage as owner.
 */
public final class Dialogs {
    /** Active theme stylesheets, wired by MainWindow; applied to every dialog. */
    private static volatile List<String> globalStylesheets = List.of();

    private Dialogs() {
    }

    public static void setGlobalStylesheets(List<String> stylesheets) {
        globalStylesheets = List.copyOf(stylesheets);
    }

    /** Create a dialog that inherits the app theme + owner window. */
    public static <T> Dialog<T> newDialog() {
        Dialog<T> d = new Dialog<>();
        style(d);
        return d;
    }

    /** Apply theme stylesheets + owner to an existing dialog. */
    public static void style(Dialog<?> d) {
        d.getDialogPane().getStylesheets().setAll(globalStylesheets);
        javafx.stage.Window owner = activeStage();
        if (owner != null) {
            d.initOwner(owner);
        }
    }

    public static void info(String title, String message) {
        alert(Alert.AlertType.INFORMATION, title, message);
    }

    public static void error(String title, String message) {
        alert(Alert.AlertType.ERROR, title, message);
    }

    public static void warn(String title, String message) {
        alert(Alert.AlertType.WARNING, title, message);
    }

    private static void alert(Alert.AlertType type, String title, String message) {
        Runnable show = () -> {
            Alert a = new Alert(type);
            a.setTitle(title);
            a.setHeaderText(null);
            a.setContentText(message);
            style(a);
            a.showAndWait();
        };
        Fx.runLater(show);
    }

    public static boolean confirm(String title, String message) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION);
        a.setTitle(title);
        a.setHeaderText(null);
        a.setContentText(message);
        style(a);
        Optional<ButtonType> result = a.showAndWait();
        return result.isPresent() && result.get().getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    /** Text input dialog; empty Optional when cancelled. */
    public static Optional<String> prompt(String title, String message, String initialValue) {
        javafx.scene.control.TextInputDialog d = new javafx.scene.control.TextInputDialog(initialValue == null ? "" : initialValue);
        d.setTitle(title);
        d.setHeaderText(null);
        d.setContentText(message);
        style(d);
        return d.showAndWait().map(String::trim);
    }

    private static javafx.stage.Stage activeStage() {
        // Window.getWindows() returns the LIVE list — a window closing during the
        // loop breaks the iterator (NoSuchElementException wrapping an
        // IndexOutOfBoundsException). Always iterate a snapshot.
        for (javafx.stage.Window w : java.util.List.copyOf(javafx.stage.Window.getWindows())) {
            if (w instanceof javafx.stage.Stage s && s.isFocused()) {
                return s;
            }
        }
        return null;
    }
}
