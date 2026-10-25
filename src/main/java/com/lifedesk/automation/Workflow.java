package com.lifedesk.automation;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A saved, schedulable workflow: name + plan steps + schedule.
 * Schedule types: NONE (manual only), DAILY (HH:mm), WEEKLY (day + HH:mm).
 */
public class Workflow {
    public String id;
    public String name = "";
    public List<PlanStep> steps = new ArrayList<>();
    /** "NONE" | "DAILY" | "WEEKLY" */
    public String scheduleType = "NONE";
    public DayOfWeek scheduleDay;      // WEEKLY only
    public LocalTime scheduleTime = LocalTime.of(8, 0);
    public boolean enabled = false;
    /** Auto-run without plan approval; rejected if any step is DESTRUCTIVE or WRITE. */
    public boolean autoApprove = false;
    public Instant lastRunAt;

    public Workflow() {
        this.id = com.lifedesk.common.Ids.newId();
    }
}
