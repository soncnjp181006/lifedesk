package com.lifedesk.habits;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/** A personal goal, optionally fed by linked tasks. */
public class Goal {
    public String id;
    public String title = "";
    public String description = "";
    public GoalStatus status = GoalStatus.ACTIVE;
    public ProgressMode progressMode = ProgressMode.MANUAL;
    public int manualProgress = 0;
    public LocalDate targetDate;
    public Set<String> linkedTaskIds = new LinkedHashSet<>();
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    public Goal() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    // Accessors (UI and rule engine read goals fluently)
    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public GoalStatus status() {
        return status;
    }

    public ProgressMode progressMode() {
        return progressMode;
    }

    public int manualProgress() {
        return manualProgress;
    }

    public java.time.LocalDate targetDate() {
        return targetDate;
    }

    public java.util.Set<String> linkedTaskIds() {
        return linkedTaskIds;
    }
}
