package com.lifedesk.tasks;

import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.TextSearch;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Task business logic: CRUD, completion with recurrence spawning,
 * filtering/search and dashboard queries. No JavaFX dependencies.
 */
public class TaskService {
    private final TaskRepository repo;
    private final TaskGroupRepository groupRepo;
    private final ChangeNotifier change = new ChangeNotifier();

    public TaskService(TaskRepository repo, TaskGroupRepository groupRepo) {
        this.repo = repo;
        this.groupRepo = groupRepo;
    }

    public ChangeNotifier changes() {
        return change;
    }

    public Task create(String title, String notes, Priority priority, LocalDate dueDate,
                       LocalTime dueTime, String group, List<String> tags, RecurrenceRule recurrence) {
        Task t = new Task();
        t.title = Validators.requireNonBlank(title, "Tiêu đề");
        t.notes = notes == null ? "" : notes;
        t.priority = priority == null ? Priority.MEDIUM : priority;
        t.dueDate = dueDate;
        t.dueTime = dueTime;
        t.group = group == null ? "" : group.trim();
        if (tags != null) {
            tags.stream().filter(s -> !s.isBlank()).map(String::trim).forEach(t.tags::add);
        }
        t.recurrence = recurrence == null ? new RecurrenceRule() : recurrence;
        repo.insert(t);
        change.fire();
        return t;
    }

    public void update(Task task) {
        task.title = Validators.requireNonBlank(task.title, "Tiêu đề");
        repo.update(task);
        change.fire();
    }

    public void delete(String id) {
        repo.delete(id);
        change.fire();
    }

    public Task get(String id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy công việc: " + id));
    }

    public List<Task> all() {
        return repo.findAll();
    }

    /** Set status; completing a recurring task spawns its next occurrence. */
    public void setStatus(String id, TaskStatus status) {
        Task t = get(id);
        t.status = status;
        t.completedAt = status == TaskStatus.DONE ? java.time.Instant.now() : null;
        if (status == TaskStatus.DONE && t.recurrence.type != RecurrenceType.NONE) {
            LocalDate next = nextOccurrence(t);
            if (next != null) {
                Task spawned = new Task();
                spawned.title = t.title;
                spawned.notes = t.notes;
                spawned.priority = t.priority;
                spawned.dueDate = next;
                spawned.dueTime = t.dueTime;
                spawned.group = t.group;
                spawned.tags = new java.util.LinkedHashSet<>(t.tags);
                spawned.checklist = new ArrayList<>(t.checklist.stream()
                        .map(c -> new ChecklistItem(c.text, false)).toList());
                spawned.recurrence = t.recurrence;
                spawned.linkedGoalIds = new java.util.LinkedHashSet<>(t.linkedGoalIds);
                spawned.linkedNoteIds = new java.util.LinkedHashSet<>(t.linkedNoteIds);
                repo.insert(spawned);
            }
        }
        repo.update(t);
        change.fire();
    }

    /** Compute the next due date for a recurring task, relative to today. */
    public static LocalDate nextOccurrence(Task t) {
        RecurrenceRule r = t.recurrence;
        LocalDate base = t.dueDate != null ? t.dueDate : LocalDate.now();
        LocalDate candidate = base;
        int guard = 0;
        while (guard++ < 10000) {
            candidate = switch (r.type) {
                case DAILY -> candidate.plusDays(Math.max(1, r.interval));
                case WEEKLY -> nextWeekly(candidate, r);
                case MONTHLY -> candidate.plusMonths(Math.max(1, r.interval));
                case NONE -> null;
            };
            if (candidate == null) {
                return null;
            }
            if (!candidate.isBefore(LocalDate.now())) {
                return candidate;
            }
        }
        return null;
    }

    private static LocalDate nextWeekly(LocalDate from, RecurrenceRule r) {
        List<Integer> days = (r.daysOfWeek == null || r.daysOfWeek.isEmpty())
                ? List.of(from.getDayOfWeek().getValue())
                : r.daysOfWeek.stream().distinct().sorted().toList();
        int interval = Math.max(1, r.interval);
        for (int d = 1; d <= 7L * interval + 7; d++) {
            LocalDate candidate = from.plusDays(d);
            if (days.contains(candidate.getDayOfWeek().getValue())
                    && weeksBetween(from, candidate) % interval == 0) {
                return candidate;
            }
        }
        return from.plusWeeks(interval);
    }

    private static long weeksBetween(LocalDate from, LocalDate to) {
        return java.time.temporal.ChronoUnit.WEEKS.between(from.with(DayOfWeek.MONDAY), to.with(DayOfWeek.MONDAY));
    }

    public List<Task> search(String query, Predicate<Task> filter, Comparator<Task> sort) {
        List<Task> result = new ArrayList<>();
        for (Task t : repo.findAll()) {
            if (filter != null && !filter.test(t)) {
                continue;
            }
            if (query == null || query.isBlank()
                    || TextSearch.matches(t.title + " " + t.notes + " " + t.group + " " + String.join(" ", t.tags), query)) {
                result.add(t);
            }
        }
        result.sort(sort != null ? sort : Comparator.comparing(Task::getDueDateOrMax));
        return result;
    }

    public List<Task> dueToday() {
        LocalDate today = LocalDate.now();
        return repo.findAll().stream().filter(t -> t.isDueToday(today)).toList();
    }

    public List<Task> overdue() {
        LocalDate today = LocalDate.now();
        return repo.findAll().stream().filter(t -> t.isOverdue(today)).toList();
    }

    public List<Task> upcoming(int days) {
        LocalDate today = LocalDate.now();
        LocalDate end = today.plusDays(days);
        return repo.findAll().stream()
                .filter(t -> t.dueDate != null && !t.isOverdue(today)
                        && !t.isDueToday(today)
                        && !t.dueDate.isAfter(end)
                        && t.status != TaskStatus.DONE && t.status != TaskStatus.CANCELLED)
                .toList();
    }

    /**
     * What the group pickers offer: every name the user saved plus every value
     * already written on a task, so free-text groups predating the list survive.
     */
    public List<String> groups() {
        java.util.TreeSet<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(groupRepo.findAll());
        repo.findAll().forEach(t -> {
            if (t.group != null && !t.group.isBlank()) {
                names.add(t.group.trim());
            }
        });
        return List.copyOf(names);
    }

    /** Tasks tagged with a group, matched case-insensitively like the picker. */
    public long groupUsage(String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        return repo.findAll().stream().filter(t -> name.equalsIgnoreCase(t.group)).count();
    }

    public void addGroup(String name) {
        String clean = Validators.requireNonBlank(name, "Tên nhóm");
        if (groups().stream().anyMatch(g -> g.equalsIgnoreCase(clean))) {
            throw new ValidationException("Nhóm \"" + clean + "\" đã tồn tại");
        }
        List<String> saved = new ArrayList<>(groupRepo.findAll());
        saved.add(clean);
        groupRepo.save(saved);
        change.fire();
    }

    /** Renames the saved entry and re-tags the tasks that carry it. */
    public void renameGroup(String from, String to) {
        String clean = Validators.requireNonBlank(to, "Tên nhóm");
        Validators.requireNonBlank(from, "Nhóm gốc");
        if (from.equalsIgnoreCase(clean)) {
            return;
        }
        if (groups().stream().anyMatch(g -> g.equalsIgnoreCase(clean))) {
            throw new ValidationException("Nhóm \"" + clean + "\" đã tồn tại");
        }
        List<String> saved = new ArrayList<>(groupRepo.findAll());
        saved.replaceAll(g -> g.equalsIgnoreCase(from) ? clean : g);
        groupRepo.save(saved);
        for (Task t : repo.findAll()) {
            if (from.equalsIgnoreCase(t.group)) {
                t.group = clean;
                repo.update(t);
            }
        }
        change.fire();
    }

    /** Refuses to orphan tasks: the group only goes once nothing uses it. */
    public void deleteGroup(String name) {
        long used = groupUsage(name);
        if (used > 0) {
            throw new ValidationException("Còn " + used + " công việc thuộc nhóm \"" + name + "\"");
        }
        List<String> saved = new ArrayList<>(groupRepo.findAll());
        if (!saved.removeIf(g -> g.equalsIgnoreCase(name))) {
            throw new NotFoundException("Không tìm thấy nhóm: " + name);
        }
        groupRepo.save(saved);
        change.fire();
    }

    public long openCount() {
        return repo.findAll().stream().filter(t -> t.status == TaskStatus.TODO || t.status == TaskStatus.IN_PROGRESS).count();
    }

    public long doneCount() {
        return repo.findAll().stream().filter(t -> t.status == TaskStatus.DONE).count();
    }
}
