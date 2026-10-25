package com.lifedesk.ui.components;

import javafx.scene.control.Label;

/** Small colored status chips (task status, priority, run states). */
public final class Chips {
    private Chips() {
    }

    public static Label chip(String text, String styleClass) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", styleClass);
        return l;
    }

    public static Label taskStatus(String status) {
        return switch (status) {
            case "TODO" -> chip("Cần làm", "chip-todo");
            case "IN_PROGRESS" -> chip("Đang làm", "chip-doing");
            case "DONE" -> chip("Hoàn thành", "chip-done");
            case "CANCELLED" -> chip("Đã hủy", "chip-cancelled");
            default -> chip(status, "chip-info");
        };
    }

    public static Label priority(String p) {
        return switch (p) {
            case "URGENT" -> chip("Khẩn cấp", "chip-urgent");
            case "HIGH" -> chip("Cao", "chip-high");
            case "MEDIUM" -> chip("Trung bình", "chip-medium");
            default -> chip("Thấp", "chip-low");
        };
    }

    public static Label runStatus(com.lifedesk.automation.RunStatus s) {
        return switch (s) {
            case PENDING -> chip("Chờ chạy", "chip-medium");
            case RUNNING -> chip("Đang chạy", "chip-info");
            case WAITING_APPROVAL -> chip("Chờ phê duyệt", "chip-urgent");
            case COMPLETED -> chip("Hoàn thành", "chip-done");
            case FAILED -> chip("Thất bại", "chip-overdue");
            case CANCELLED -> chip("Đã hủy", "chip-cancelled");
        };
    }

    public static Label overdue(String text) {
        return chip(text, "chip-overdue");
    }
}
