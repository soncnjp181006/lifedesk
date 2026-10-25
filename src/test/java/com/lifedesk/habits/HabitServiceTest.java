package com.lifedesk.habits;

import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.StorageIssueLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HabitServiceTest {

    @TempDir
    Path dir;

    private HabitService service() {
        return new HabitService(new JsonStore(dir, new StorageIssueLog()));
    }

    @Test
    void streakCountsConsecutiveScheduledDays() {
        HabitService svc = service();
        Habit h = svc.addHabit("Đọc sách", null, null, "accent"); // daily (empty schedule = every day)
        svc.setCompletion(h.id, LocalDate.now(), true, null, "user");
        assertEquals(1, svc.currentStreak(h));
        svc.setCompletion(h.id, LocalDate.now().minusDays(1), true, "bù trễ 1 ngày", "user");
        assertEquals(2, svc.currentStreak(h));
        // gap at day-3 keeps streak at 2
        svc.setCompletion(h.id, LocalDate.now().minusDays(3), true, "bù trễ 3 ngày", "user");
        assertEquals(2, svc.currentStreak(h));
    }

    @Test
    void scheduledDaysOnlyCountOnSchedule() {
        HabitService svc = service();
        Habit weekday = svc.addHabit("Tập gym", null, Set.of(DayOfWeek.MONDAY), "accent");
        // Complete every day this week so far; only Mondays count for the streak.
        LocalDate today = LocalDate.now();
        for (LocalDate d = today.minusDays(10); !d.isAfter(today); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.MONDAY) {
                svc.setCompletion(weekday.id, d, true, "bù lịch sử " + d, "user");
            }
        }
        assertEquals(1, svc.currentStreak(weekday)); // only today-or-last Monday
    }

    @Test
    void retroactiveAdjustmentRequiresReasonAndLogs() {
        HabitService svc = service();
        Habit h = svc.addHabit("Thiền", null, null, "accent");
        assertThrows(RuntimeException.class,
                () -> svc.setCompletion(h.id, LocalDate.now().minusDays(1), true, null, "user"));
        svc.setCompletion(h.id, LocalDate.now().minusDays(1), true, "quên bấm hôm qua", "user");
        assertTrue(svc.changeLog().stream().anyMatch(e ->
                e.reason.equals("quên bấm hôm qua") && e.entityType.equals("habit")));
        // Same-day completion needs no reason
        svc.setCompletion(h.id, LocalDate.now(), true, null, "user");
        assertTrue(svc.isCompleted(h.id, LocalDate.now()));
    }

    @Test
    void completionRateUsesScheduledDaysOnly() {
        HabitService svc = service();
        Habit h = svc.addHabit("Uống nước", null, Set.of(), "accent");
        svc.setCompletion(h.id, LocalDate.now(), true, null, "user");
        double rate = svc.completionRate(h, 10);
        assertTrue(rate > 0 && rate <= 0.1, "1 of last 10 days completed, got " + rate);
    }

    @Test
    void weeklySummaryCountsCurrentWeek() {
        HabitService svc = service();
        Habit h = svc.addHabit("Học từ vựng", null, null, "accent");
        svc.setCompletion(h.id, LocalDate.now(), true, null, "user");
        var summary = svc.weeklySummary();
        assertFalse(summary.isEmpty());
        assertTrue(summary.stream().anyMatch(s -> s.habitName().equals("Học từ vựng") && s.completed() >= 1));
    }

    @Test
    void goalAutoProgressFromLinkedTasks() {
        HabitService svc = service();
        Goal g = svc.addGoal("Đọc 5 sách", "", LocalDate.now().plusMonths(3));
        g.progressMode = ProgressMode.AUTO_TASKS;
        g.linkedTaskIds.add("t1");
        g.linkedTaskIds.add("t2");
        long done = 1;
        assertEquals(50, svc.progressOf(g, done, g.linkedTaskIds.size()));
    }

    @Test
    void goalHistoryRecordedOnUpdate() {
        HabitService svc = service();
        Goal g = svc.addGoal("Mục tiêu A", "", null);
        g.manualProgress = 40;
        svc.updateGoal(g, "cập nhật tiến độ", "user");
        assertTrue(svc.changeLog().stream().anyMatch(e ->
                e.entityType.equals("goal") && e.reason.equals("cập nhật tiến độ")));
    }
}
