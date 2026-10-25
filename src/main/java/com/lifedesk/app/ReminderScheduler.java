package com.lifedesk.app;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.config.AppConfig;
import com.lifedesk.habits.Habit;
import com.lifedesk.habits.HabitService;
import com.lifedesk.notifications.NotificationService;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import com.lifedesk.tasks.Task;
import com.lifedesk.tasks.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Local reminder scheduler: checks task due dates and habit schedules every
 * minute and raises in-app notifications (no OS toasts, fully offline).
 * Deduplication keys are persisted so a restart never re-spams.
 */
public class ReminderScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(ReminderScheduler.class);
    private static final String SENT_FILE = "notifications/reminders-sent.json";

    private final JsonStore store;
    private final TaskService taskService;
    private final HabitService habitService;
    private final NotificationService notifications;
    private final AppConfig config;
    private final Set<String> sentKeys = new HashSet<>();
    private LocalDate sentDate = LocalDate.now();

    public ReminderScheduler(JsonStore store, TaskService taskService, HabitService habitService,
                             NotificationService notifications, AppConfig config) {
        this.store = store;
        this.taskService = taskService;
        this.habitService = habitService;
        this.notifications = notifications;
        this.config = config;
        load();
    }

    private void load() {
        Type type = new TypeToken<List<String>>() {
        }.getType();
        JsonStore.StoredDoc doc = store.read(SENT_FILE, SchemaMigrator.none(1));
        if (doc != null) {
            if (doc.data().has("date")) {
                sentDate = LocalDate.parse(doc.data().get("date").getAsString());
            }
            if (doc.data().has("keys")) {
                List<String> keys = GsonFactory.create().fromJson(doc.data().getAsJsonArray("keys"), type);
                if (keys != null) {
                    sentKeys.addAll(keys);
                }
            }
        }
        if (!sentDate.isEqual(LocalDate.now())) {
            sentKeys.clear();
            sentDate = LocalDate.now();
            save();
        }
    }

    private void save() {
        JsonObject data = new JsonObject();
        data.addProperty("date", sentDate.toString());
        data.add("keys", GsonFactory.create().toJsonTree(new java.util.ArrayList<>(sentKeys)));
        store.write(SENT_FILE, 1, data);
    }

    public void start(ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(this::check, 20, 60, TimeUnit.SECONDS);
        LOG.info("Reminder scheduler started");
    }

    /** One sweep; safe to call from tests. */
    public void check() {
        if (!config.remindersEnabled) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            if (!today.isEqual(sentDate)) {
                sentKeys.clear();
                sentDate = today;
            }
            LocalTime now = LocalTime.now();
            checkTasks(today, now);
            checkHabits(today, now);
        } catch (RuntimeException e) {
            LOG.error("Reminder check failed", e);
        }
    }

    private void checkTasks(LocalDate today, LocalTime now) {
        for (Task t : taskService.all()) {
            if (t.dueDate == null || t.status == com.lifedesk.tasks.TaskStatus.DONE
                    || t.status == com.lifedesk.tasks.TaskStatus.CANCELLED) {
                continue;
            }
            if (t.dueDate.isBefore(today)) {
                notifyOnce("task-overdue-" + t.id + "-" + today,
                        "Công việc quá hạn",
                        t.title + " đã quá hạn (" + t.dueDate + ")",
                        "tasks", t.id);
            } else if (t.dueDate.isEqual(today)) {
                if (t.dueTime != null) {
                    // Minutes-until-due handles the midnight wrap correctly
                    // (LocalTime +/- comparisons break when the window crosses 00:00).
                    long minsToDue = java.time.temporal.ChronoUnit.MINUTES.between(now, t.dueTime);
                    if (minsToDue <= config.reminderMinutesBefore && minsToDue >= -30) {
                        notifyOnce("task-due-" + t.id + "-" + today,
                                "Sắp đến hạn",
                                t.title + " lúc " + t.dueTime,
                                "tasks", t.id);
                    }
                } else if (now.isAfter(LocalTime.of(9, 0))) {
                    notifyOnce("task-today-" + t.id + "-" + today,
                            "Việc hôm nay",
                            t.title,
                            "tasks", t.id);
                }
            }
        }
    }

    private void checkHabits(LocalDate today, LocalTime now) {
        for (Habit h : habitService.habits(false)) {
            if (!h.scheduledOn(today.getDayOfWeek()) || h.reminderTime == null) {
                continue;
            }
            if (!now.isBefore(h.reminderTime) && now.isBefore(h.reminderTime.plusMinutes(30))) {
                if (!habitService.isCompleted(h.id, today)) {
                    notifyOnce("habit-" + h.id + "-" + today,
                            "Đến giờ thói quen",
                            h.name,
                            "habits", h.id);
                }
            }
        }
    }

    private void notifyOnce(String key, String title, String message, String view, String refId) {
        if (!sentKeys.add(key)) {
            return;
        }
        notifications.push(title, message, "INFO", view, refId);
        save();
        LOG.debug("Reminder fired: {}", key);
    }

    /** Exposed for tests: whether a key was already sent. */
    public boolean wasSent(String key) {
        return sentKeys.contains(key);
    }
}
