package com.lifedesk.tasks;

import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.StorageIssueLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {

    @TempDir
    Path dir;

    private TaskService service() {
        return new TaskService(new JsonTaskRepository(new JsonStore(dir, new StorageIssueLog())));
    }

    @Test
    void createValidatesTitle() {
        TaskService svc = service();
        assertThrows(RuntimeException.class, () -> svc.create("   ", null, null, null, null, "", null, null));
    }

    @Test
    void updateAndDelete() {
        TaskService svc = service();
        Task t = svc.create("A", "notes", Priority.HIGH, LocalDate.now(), null, "g", List.of("x"), null);
        t.title = "A2";
        t.priority = Priority.URGENT;
        svc.update(t);
        assertEquals("A2", svc.get(t.id).title);
        assertEquals(Priority.URGENT, svc.get(t.id).priority);
        svc.delete(t.id);
        assertThrows(RuntimeException.class, () -> svc.get(t.id));
    }

    @Test
    void dailyRecurrenceSpawnsNextOccurrence() {
        TaskService svc = service();
        Task t = svc.create("Đerez hàng ngày", null, Priority.LOW, LocalDate.now(), null, "", null, null);
        t.recurrence.type = RecurrenceType.DAILY;
        t.recurrence.interval = 2;
        svc.update(t);
        svc.setStatus(t.id, TaskStatus.DONE);

        LocalDate next = TaskService.nextOccurrence(t);
        assertNotNull(next);
        assertEquals(LocalDate.now().plusDays(2), next);
        // a fresh TODO copy exists with the future due date
        assertTrue(svc.all().stream().anyMatch(x -> x.title.equals("Đerez hàng ngày")
                && x.status == TaskStatus.TODO && x.dueDate.equals(LocalDate.now().plusDays(2))));
    }

    @Test
    void weeklyRecurrenceChoosesScheduledDay() {
        TaskService svc = service();
        Task t = svc.create("Họp tuần", null, Priority.MEDIUM, LocalDate.now(), null, "", null, null);
        t.recurrence.type = RecurrenceType.WEEKLY;
        t.recurrence.daysOfWeek = List.of(1, 5); // Monday & Friday
        svc.update(t);
        LocalDate next = TaskService.nextOccurrence(t);
        assertNotNull(next);
        assertTrue(next.getDayOfWeek().getValue() == 1 || next.getDayOfWeek().getValue() == 5);
        assertTrue(next.isAfter(LocalDate.now()));
    }

    @Test
    void todayOverdueUpcomingFilters() {
        TaskService svc = service();
        svc.create("Hôm nay", null, Priority.HIGH, LocalDate.now(), null, "", null, null);
        Task past = svc.create("Quá hạn", null, Priority.URGENT, LocalDate.now().minusDays(3), null, "", null, null);
        svc.create("Tương lai", null, Priority.LOW, LocalDate.now().plusDays(5), null, "", null, null);

        assertEquals(1, svc.dueToday().size());
        assertTrue(svc.overdue().stream().anyMatch(t -> t.id.equals(past.id)));
        assertEquals(1, svc.upcoming(7).size());
    }

    @Test
    void completingTaskSetsTimestamp() {
        TaskService svc = service();
        Task t = svc.create("X", null, Priority.MEDIUM, null, null, "", null, null);
        assertNull(svc.get(t.id).completedAt);
        svc.setStatus(t.id, TaskStatus.DONE);
        assertNotNull(svc.get(t.id).completedAt);
        assertEquals(1, svc.doneCount());
    }

    @Test
    void searchMatchesTitleNotesTagsCaseAndDiacriticInsensitive() {
        TaskService svc = service();
        svc.create("Học Tiếng Nhật", "kanji N3", Priority.MEDIUM, null, null, "edu", List.of("ngôn ngữ"), null);
        assertTrue(!svc.search("hoc tieng", null, null).isEmpty());
        assertTrue(!svc.search("kanji", null, null).isEmpty());
        assertTrue(!svc.search("ngon ngu", null, null).isEmpty());
        assertTrue(svc.search("không có", null, null).isEmpty());
    }
}
