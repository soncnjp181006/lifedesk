package com.lifedesk.tasks;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Task domain model. Public fields are the persistence model (JSON). */
public class Task {
    public String id;
    public String title = "";
    public String notes = "";
    public TaskStatus status = TaskStatus.TODO;
    public Priority priority = Priority.MEDIUM;
    public LocalDate dueDate;
    public LocalTime dueTime;
    /** Free-form group (project / phân nhóm). */
    public String group = "";
    public Set<String> tags = new LinkedHashSet<>();
    public List<ChecklistItem> checklist = new ArrayList<>();
    public RecurrenceRule recurrence = new RecurrenceRule();
    /** Linked records from other modules. */
    public Set<String> linkedNoteIds = new LinkedHashSet<>();
    public Set<String> linkedGoalIds = new LinkedHashSet<>();
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    public Instant completedAt;

    public Task() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public boolean isOverdue(LocalDate today) {
        return dueDate != null && status != TaskStatus.DONE && status != TaskStatus.CANCELLED
                && dueDate.isBefore(today);
    }

    /** Sort key: tasks without a due date sort last. */
    public LocalDate getDueDateOrMax() {
        return dueDate != null ? dueDate : LocalDate.MAX;
    }

    public boolean isDueToday(LocalDate today) {
        return dueDate != null && status != TaskStatus.DONE && status != TaskStatus.CANCELLED
                && dueDate.isEqual(today);
    }

    /** Progress of the checklist (0..100). Tasks without checklist report 0/100 by status. */
    public int checklistProgress() {
        if (checklist.isEmpty()) {
            return status == TaskStatus.DONE ? 100 : 0;
        }
        long done = checklist.stream().filter(c -> c.done).count();
        return (int) Math.round(100.0 * done / checklist.size());
    }
}
