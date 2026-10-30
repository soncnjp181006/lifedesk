package com.lifedesk.habits;

import com.lifedesk.tasks.Task;
import com.lifedesk.tasks.TaskStatus;

import java.util.List;

/**
 * Goal progress computation shared by the habits view and the dashboard:
 * manual percent, or the share of linked tasks that are done.
 */
public final class GoalProgress {
    private GoalProgress() {
    }

    /** Percent 0..100 for {@code g} against the given (usually all) tasks. */
    public static int percent(Goal g, List<Task> tasks) {
        if (g.progressMode() == ProgressMode.MANUAL) {
            return g.manualProgress();
        }
        long total = g.linkedTaskIds().size();
        if (total == 0) {
            return 0;
        }
        long done = tasks.stream()
                .filter(t -> g.linkedTaskIds().contains(t.id) && t.status == TaskStatus.DONE)
                .count();
        return (int) Math.round(100.0 * done / total);
    }
}
