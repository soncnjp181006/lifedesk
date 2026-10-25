package com.lifedesk.habits;

import java.time.Instant;

/** Audit entry for history adjustments on habits and goals. */
public class ChangeLogEntry {
    public String id;
    public Instant at = Instant.now();
    /** "habit" | "goal" */
    public String entityType;
    public String entityId;
    public String entityName;
    public String field;
    public String oldValue;
    public String newValue;
    /** Who made the change: "user" or "agent:<runId>". */
    public String actor = "user";
    public String reason = "";

    public ChangeLogEntry() {
        this.id = com.lifedesk.common.Ids.newId();
    }
}
