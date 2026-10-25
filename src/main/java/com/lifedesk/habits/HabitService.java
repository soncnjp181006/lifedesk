package com.lifedesk.habits;

import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Habits and goals business logic with streak statistics, retroactive
 * history adjustments (every change recorded in a change log) and weekly
 * summaries. Log entries are partitioned by year: habits/logs-YYYY.json.
 */
public class HabitService {
    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();

    private final List<Habit> habits = new ArrayList<>();
    /** habitId -> (date -> log), partitioned per year on disk. */
    private final Map<String, Map<LocalDate, HabitLog>> logs = new HashMap<>();
    private final List<Goal> goals = new ArrayList<>();
    private final List<ChangeLogEntry> changeLog = new ArrayList<>();
    private final java.util.Set<Integer> loadedLogYears = new java.util.HashSet<>();
    /** Snapshots for change detection (UI mutates shared instances, so diffs must be against last persisted state). */
    private final java.util.Map<String, String[]> habitSnapshots = new java.util.HashMap<>();
    private final java.util.Map<String, String[]> goalSnapshots = new java.util.HashMap<>();

    public HabitService(JsonStore store) {
        this.store = store;
        loadHabits();
        loadGoals();
        loadChangeLog();
        loadLogsForYear(LocalDate.now().getYear());
        habits.forEach(this::snapshotHabit);
        goals.forEach(this::snapshotGoal);
    }

    public ChangeNotifier changes() {
        return change;
    }

    // ------------------------------------------------------------------ habits

    private void loadHabits() {
        TypeTokenHolder h = new TypeTokenHolder<>();
        List<Habit> parsed = readList("habits/habits.json", "habits", h.listOf(Habit.class));
        habits.addAll(parsed);
    }

    private void loadGoals() {
        TypeTokenHolder h = new TypeTokenHolder<>();
        goals.addAll(readList("habits/goals.json", "goals", h.listOf(Goal.class)));
    }

    private void loadChangeLog() {
        TypeTokenHolder h = new TypeTokenHolder<>();
        changeLog.addAll(readList("habits/changelog.json", "entries", h.listOf(ChangeLogEntry.class)));
    }

    private void loadLogsForYear(int year) {
        if (!loadedLogYears.add(year)) {
            return;
        }
        TypeTokenHolder h = new TypeTokenHolder<>();
        List<HabitLog> parsed = readList("habits/logs-" + year + ".json", "logs", h.listOf(HabitLog.class));
        for (HabitLog log : parsed) {
            logs.computeIfAbsent(log.habitId, k -> new HashMap<>()).put(log.date, log);
        }
    }

    private <T> List<T> readList(String file, String key, java.lang.reflect.Type listType) {
        JsonStore.StoredDoc doc = store.read(file, SchemaMigrator.none(1));
        if (doc == null || !doc.data().has(key) || !doc.data().get(key).isJsonArray()) {
            return new ArrayList<>();
        }
        List<T> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray(key), listType);
        return parsed == null ? new ArrayList<>() : parsed;
    }

    private void writeList(String file, String key, Object list) {
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.add(key, GsonFactory.create().toJsonTree(list));
        store.write(file, 1, data);
    }

    private void persistHabits() {
        writeList("habits/habits.json", "habits", habits);
    }

    private void persistGoals() {
        writeList("habits/goals.json", "goals", goals);
    }

    private void persistChangeLog() {
        List<ChangeLogEntry> capped = changeLog.size() > 1000
                ? changeLog.subList(changeLog.size() - 1000, changeLog.size())
                : changeLog;
        writeList("habits/changelog.json", "entries", new ArrayList<>(capped));
    }

    private void persistLogs(int year) {
        List<HabitLog> forYear = new ArrayList<>();
        for (Map<LocalDate, HabitLog> byDate : logs.values()) {
            for (Map.Entry<LocalDate, HabitLog> e : byDate.entrySet()) {
                if (e.getKey().getYear() == year) {
                    forYear.add(e.getValue());
                }
            }
        }
        writeList("habits/logs-" + year + ".json", "logs", forYear);
    }

    public List<Habit> habits(boolean includeArchived) {
        return habits.stream().filter(h -> includeArchived || !h.archived)
                .sorted(Comparator.comparing(h -> h.name)).toList();
    }

    public Habit habit(String id) {
        return habits.stream().filter(h -> h.id.equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy thói quen: " + id));
    }

    public Habit addHabit(String name, String description, java.util.Set<DayOfWeek> days, String color) {
        Habit h = new Habit();
        h.name = Validators.requireNonBlank(name, "Tên thói quen");
        h.description = description == null ? "" : description;
        if (days != null) {
            h.scheduleDays = new java.util.LinkedHashSet<>(days);
        }
        h.color = color == null || color.isBlank() ? "accent" : color;
        habits.add(h);
        persistHabits();
        snapshotHabit(h);
        change.fire();
        return h;
    }

    public void updateHabit(Habit updated, String reason, String actor) {
        habit(updated.id); // existence check
        Validators.requireNonBlank(updated.name, "Tên thói quen");
        String[] old = habitSnapshots.getOrDefault(updated.id,
                new String[]{updated.name, String.valueOf(updated.description), updated.scheduleDays.toString()});
        if (!old[0].equals(updated.name)) {
            addChangeLog("habit", updated.id, old[0], "name", old[0], updated.name, actor, reason);
        }
        if (!old[1].equals(String.valueOf(updated.description))) {
            addChangeLog("habit", updated.id, updated.name, "description", old[1],
                    String.valueOf(updated.description), actor, reason);
        }
        if (!old[2].equals(updated.scheduleDays.toString())) {
            addChangeLog("habit", updated.id, updated.name, "scheduleDays", old[2],
                    updated.scheduleDays.toString(), actor, reason);
        }
        persistHabits();
        snapshotHabit(updated);
        change.fire();
    }

    public void deleteHabit(String id) {
        if (!habits.removeIf(h -> h.id.equals(id))) {
            throw new NotFoundException("Không tìm thấy thói quen: " + id);
        }
        logs.remove(id);
        persistHabits();
        int currentYear = LocalDate.now().getYear();
        persistLogs(currentYear);
        change.fire();
    }

    /**
     * Record (or change) completion for a habit on a date. Any retroactive
     * (past-date) change requires a reason, which is stored in the change log.
     */
    public void setCompletion(String habitId, LocalDate date, boolean completed, String reason, String actor) {
        Habit habit = habit(habitId);
        if (date.isAfter(LocalDate.now())) {
            throw new ValidationException("Không thể ghi nhận hoàn thành cho ngày tương lai");
        }
        loadLogsForYear(date.getYear());
        Map<LocalDate, HabitLog> byDate = logs.computeIfAbsent(habitId, k -> new HashMap<>());
        HabitLog existing = byDate.get(date);
        if (existing != null && existing.completed == completed) {
            return;
        }
        boolean retroactive = date.isBefore(LocalDate.now());
        if (retroactive && (reason == null || reason.isBlank())) {
            throw new ValidationException("Điều chỉnh lịch sử cần ghi rõ lý do");
        }
        String oldValue = existing == null ? "chưa ghi" : String.valueOf(existing.completed);
        HabitLog log = existing == null ? new HabitLog(habitId, date, completed) : existing;
        log.completed = completed;
        log.adjustmentReason = reason == null ? "" : reason;
        log.updatedAt = java.time.Instant.now();
        byDate.put(date, log);
        persistLogs(date.getYear());
        if (retroactive) {
            addChangeLog("habit", habit.id, habit.name, "completion@" + date,
                    oldValue, String.valueOf(completed), actor, reason);
        }
        change.fire();
    }

    public boolean isCompleted(String habitId, LocalDate date) {
        Map<LocalDate, HabitLog> byDate = logs.get(habitId);
        HabitLog log = byDate == null ? null : byDate.get(date);
        return log != null && log.completed;
    }

    /** Current streak: consecutive scheduled days completed, counting backwards from today. */
    public int currentStreak(Habit habit) {
        LocalDate day = LocalDate.now();
        int streak = 0;
        int guard = 0;
        while (guard++ < 3650) {
            if (habit.scheduledOn(day.getDayOfWeek())) {
                if (day.isEqual(LocalDate.now()) && !isCompleted(habit.id, day)) {
                    // Today still pending; don't break the streak, don't count it.
                } else if (isCompleted(habit.id, day)) {
                    streak++;
                } else {
                    break;
                }
            }
            day = day.minusDays(1);
        }
        return streak;
    }

    /** Best streak within the trailing {@code days} window. */
    public int bestStreak(Habit habit, int days) {
        int best = 0;
        int current = 0;
        for (LocalDate d = LocalDate.now().minusDays(days); !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            if (!habit.scheduledOn(d.getDayOfWeek())) {
                continue;
            }
            if (isCompleted(habit.id, d)) {
                current++;
                best = Math.max(best, current);
            } else if (!d.isEqual(LocalDate.now())) {
                current = 0;
            }
        }
        return best;
    }

    /** Completion ratio over the last {@code days} (only scheduled days count). */
    public double completionRate(Habit habit, int days) {
        int scheduled = 0;
        int done = 0;
        for (LocalDate d = LocalDate.now().minusDays(days - 1L); !d.isAfter(LocalDate.now()); d = d.plusDays(1)) {
            if (!habit.scheduledOn(d.getDayOfWeek())) {
                continue;
            }
            scheduled++;
            if (isCompleted(habit.id, d)) {
                done++;
            }
        }
        return scheduled == 0 ? 0 : (double) done / scheduled;
    }

    public record WeeklySummary(LocalDate weekStart, String habitName, int scheduled, int completed, int streak) {
    }

    /** Weekly review: per-habit scheduled vs completed for the current week (Mon-Sun). */
    public List<WeeklySummary> weeklySummary() {
        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        List<WeeklySummary> out = new ArrayList<>();
        for (Habit h : habits(false)) {
            int scheduled = 0;
            int completed = 0;
            for (LocalDate d = monday; !d.isAfter(today); d = d.plusDays(1)) {
                if (!h.scheduledOn(d.getDayOfWeek())) {
                    continue;
                }
                scheduled++;
                if (isCompleted(h.id, d)) {
                    completed++;
                }
            }
            out.add(new WeeklySummary(monday, h.name, scheduled, completed, currentStreak(h)));
        }
        return out;
    }

    // ------------------------------------------------------------------ goals

    public List<Goal> goals() {
        return goals.stream()
                .sorted(Comparator.comparing((Goal g) -> g.status).thenComparing(g -> g.targetDate == null ? LocalDate.MAX : g.targetDate))
                .toList();
    }

    public Goal goal(String id) {
        return goals.stream().filter(g -> g.id.equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy mục tiêu: " + id));
    }

    public Goal addGoal(String title, String description, LocalDate targetDate) {
        Goal g = new Goal();
        g.title = Validators.requireNonBlank(title, "Tên mục tiêu");
        g.description = description == null ? "" : description;
        g.targetDate = targetDate;
        goals.add(g);
        persistGoals();
        snapshotGoal(g);
        change.fire();
        return g;
    }

    public void updateGoal(Goal updated, String reason, String actor) {
        String[] old = goalSnapshots.getOrDefault(updated.id,
                new String[]{updated.title, updated.progressMode.name(), String.valueOf(updated.manualProgress)});
        if (!old[0].equals(updated.title)) {
            addChangeLog("goal", updated.id, old[0], "title", old[0], updated.title, actor, reason);
        }
        if (!old[1].equals(updated.progressMode.name())) {
            addChangeLog("goal", updated.id, updated.title, "progressMode", old[1],
                    updated.progressMode.name(), actor, reason);
        }
        if (!old[2].equals(String.valueOf(updated.manualProgress))) {
            addChangeLog("goal", updated.id, updated.title, "manualProgress", old[2],
                    String.valueOf(updated.manualProgress), actor, reason);
        }
        updated.updatedAt = Instant.now();
        persistGoals();
        snapshotGoal(updated);
        change.fire();
    }

    public void deleteGoal(String id) {
        if (!goals.removeIf(g -> g.id.equals(id))) {
            throw new NotFoundException("Không tìm thấy mục tiêu: " + id);
        }
        persistGoals();
        change.fire();
    }

    /** Progress of one goal; auto mode is computed by the caller (needs TaskService). */
    public int progressOf(Goal g, long linkedTasksDone, long linkedTasksTotal) {
        if (g.progressMode == ProgressMode.AUTO_TASKS) {
            return linkedTasksTotal == 0 ? 0 : (int) Math.round(100.0 * linkedTasksDone / linkedTasksTotal);
        }
        return g.manualProgress;
    }

    public List<ChangeLogEntry> changeLog() {
        return List.copyOf(changeLog);
    }

    private void addChangeLog(String entityType, String entityId, String entityName,
                              String field, String oldValue, String newValue, String actor, String reason) {
        ChangeLogEntry e = new ChangeLogEntry();
        e.entityType = entityType;
        e.entityId = entityId;
        e.entityName = entityName;
        e.field = field;
        e.oldValue = oldValue;
        e.newValue = newValue;
        e.actor = actor == null ? "user" : actor;
        e.reason = reason == null ? "" : reason;
        changeLog.add(e);
        persistChangeLog();
    }

    private void snapshotHabit(Habit h) {
        habitSnapshots.put(h.id, new String[]{h.name, String.valueOf(h.description), h.scheduleDays.toString()});
    }

    private void snapshotGoal(Goal g) {
        goalSnapshots.put(g.id, new String[]{g.title, g.progressMode.name(), String.valueOf(g.manualProgress)});
    }

    /** Helper to build parameterized list types without repeating boilerplate. */
    private static final class TypeTokenHolder<T> {
        java.lang.reflect.Type listOf(Class<T> clazz) {
            return com.google.gson.reflect.TypeToken.getParameterized(List.class, clazz).getType();
        }
    }
}
