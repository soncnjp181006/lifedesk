package com.lifedesk.tasks;

import java.util.ArrayList;
import java.util.List;

/** Recurrence rule. daysOfWeek uses java.time numbering: 1=Monday .. 7=Sunday. */
public class RecurrenceRule {
    public RecurrenceType type = RecurrenceType.NONE;
    /** Interval between recurrences (>= 1). */
    public int interval = 1;
    /** For WEEKLY rules: which days of week. Empty = same weekday as the due date. */
    public List<Integer> daysOfWeek = new ArrayList<>();
}
