package com.lifedesk.ui.components;

import javafx.scene.control.Label;

/** Small colored status chips (task status, priority, run states). */
public final class Chips {
    private Chips() {
    }

    public static Label chip(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", styleClass);
        // A chip is one fact — "Trung bình", "chuỗi 3 ngày". Left free to shrink, it
        // loses letters before the sentence beside it does, because a Label's own
        // minimum is only its longest word. Pinned here, a tight row shortens the
        // text instead of the status, which is the part the user can still read.
        l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return l;
    }

    /** Vietnamese display name — single source for chips and editor dialogs. */
    public static String taskStatusName(String status) {
        return switch (status) {
            case "TODO" -> "Cần làm";
            case "IN_PROGRESS" -> "Đang làm";
            case "DONE" -> "Hoàn thành";
            case "CANCELLED" -> "Đã hủy";
            default -> status;
        };
    }

    /** Vietnamese display name — single source for chips and editor dialogs. */
    public static String priorityName(String p) {
        return switch (p) {
            case "URGENT" -> "Khẩn cấp";
            case "HIGH" -> "Cao";
            case "MEDIUM" -> "Trung bình";
            default -> "Thấp";
        };
    }

    public static Label taskStatus(String status) {
        return chip(taskStatusName(status), switch (status) {
            case "TODO" -> "chip-todo";
            case "IN_PROGRESS" -> "chip-doing";
            case "DONE" -> "chip-done";
            case "CANCELLED" -> "chip-cancelled";
            default -> "chip-info";
        });
    }

    public static Label priority(String p) {
        return chip(priorityName(p), switch (p) {
            case "URGENT" -> "chip-urgent";
            case "HIGH" -> "chip-high";
            case "MEDIUM" -> "chip-medium";
            default -> "chip-low";
        });
    }

    public static Label overdue(String text) {
        return chip(text, "chip-overdue");
    }
}
