package com.lifedesk.habits;

import java.time.Instant;
import java.time.LocalDate;

/** One completion record for one habit on one date. */
public class HabitLog {
    public String habitId;
    public LocalDate date;
    public boolean completed;
    /** Free-text reason, required when history is adjusted retroactively. */
    public String adjustmentReason = "";
    public Instant updatedAt = Instant.now();

    public HabitLog() {
    }

    public HabitLog(String habitId, LocalDate date, boolean completed) {
        this.habitId = habitId;
        this.date = date;
        this.completed = completed;
    }
}
