package com.lifedesk.habits;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Set;

/** A recurring habit the user wants to build. */
public class Habit {
    public String id;
    public String name = "";
    public String description = "";
    /** Days of week the habit is scheduled. Empty = every day. */
    public Set<DayOfWeek> scheduleDays = new LinkedHashSet<>();
    public String color = "accent";
    public LocalTime reminderTime;
    public Instant createdAt = Instant.now();
    public boolean archived = false;

    public Habit() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public boolean scheduledOn(DayOfWeek day) {
        return scheduleDays.isEmpty() || scheduleDays.contains(day);
    }
}
