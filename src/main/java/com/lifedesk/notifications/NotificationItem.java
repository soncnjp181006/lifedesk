package com.lifedesk.notifications;

import java.time.Instant;

/** One in-app notification (reminders, background errors, agent events). */
public class NotificationItem {
    public String id;
    public String title = "";
    public String message = "";
    /** "INFO" | "WARNING" | "ERROR" */
    public String severity = "INFO";
    public Instant createdAt = Instant.now();
    public boolean read = false;
    /** Optional navigation target: view id + record id. */
    public String refView = "";
    public String refId = "";

    public NotificationItem() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public NotificationItem(String title, String message, String severity, String refView, String refId) {
        this();
        this.title = title;
        this.message = message;
        this.severity = severity;
        this.refView = refView;
        this.refId = refId;
    }
}
