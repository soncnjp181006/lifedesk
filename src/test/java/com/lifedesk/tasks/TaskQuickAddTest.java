package com.lifedesk.tasks;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskQuickAddTest {

    @Test
    void plainTitleGetsDefaults() {
        var p = TaskQuickAdd.parse("Mua quà sinh nhật mẹ");
        assertEquals("Mua quà sinh nhật mẹ", p.title());
        assertNull(p.dueDate());
        assertNull(p.dueTime());
        assertEquals(Priority.MEDIUM, p.priority());
        assertEquals("", p.group());
        assertTrue(p.tags().isEmpty());
    }

    @Test
    void parsesFullLine() {
        var p = TaskQuickAdd.parse("Nộp báo cáo @mai 9h30 #work !cao +CôngViệc");
        assertEquals("Nộp báo cáo", p.title());
        assertEquals(LocalDate.now().plusDays(1), p.dueDate());
        assertEquals(LocalTime.of(9, 30), p.dueTime());
        assertEquals(Priority.HIGH, p.priority());
        assertEquals("CôngViệc", p.group());
        assertEquals(java.util.List.of("work"), p.tags());
    }

    @Test
    void blankAndTokenOnlyLinesAreNotTasks() {
        assertNull(TaskQuickAdd.parse(null));
        assertNull(TaskQuickAdd.parse("   "));
        assertNull(TaskQuickAdd.parse("@mai 9h #work !cao"));
    }

    @Test
    void timeFormats() {
        assertEquals(LocalTime.of(9, 0), TaskQuickAdd.parseTimeToken("9h"));
        assertEquals(LocalTime.of(9, 30), TaskQuickAdd.parseTimeToken("9h30"));
        assertEquals(LocalTime.of(14, 5), TaskQuickAdd.parseTimeToken("14:05"));
        assertNull(TaskQuickAdd.parseTimeToken("25h"));
        assertNull(TaskQuickAdd.parseTimeToken("9h70"));
        assertNull(TaskQuickAdd.parseTimeToken("9 giờ"));
    }

    @Test
    void dateKeywords() {
        assertEquals(LocalDate.now(), TaskQuickAdd.parseDateToken("homnay"));
        assertEquals(LocalDate.now().plusDays(1), TaskQuickAdd.parseDateToken("mai"));
        assertEquals(LocalDate.now().minusDays(1), TaskQuickAdd.parseDateToken("qua"));
        assertEquals(LocalDate.now().plusWeeks(1), TaskQuickAdd.parseDateToken("tuansau"));
        assertNull(TaskQuickAdd.parseDateToken("khongphai"));
    }

    @Test
    void weekdayResolvesToNextOccurrence() {
        LocalDate today = LocalDate.now();
        LocalDate monday = TaskQuickAdd.parseDateToken("thu2");
        int expectedDiff = (DayOfWeek.MONDAY.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        if (expectedDiff == 0) {
            expectedDiff = 7;
        }
        assertEquals(today.plusDays(expectedDiff), monday);
        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());
    }

    @Test
    void isoAndSlashDates() {
        LocalDate d = LocalDate.now();
        assertEquals(d, TaskQuickAdd.parseDateToken(d.toString()));
        String slash = d.getDayOfMonth() + "/" + d.getMonthValue();
        assertEquals(d, TaskQuickAdd.parseDateToken(slash));
    }

    @Test
    void priorityTokens() {
        assertEquals(Priority.URGENT, TaskQuickAdd.parsePriorityToken("!khan"));
        assertEquals(Priority.URGENT, TaskQuickAdd.parsePriorityToken("!khẩn"));
        assertEquals(Priority.HIGH, TaskQuickAdd.parsePriorityToken("!cao"));
        assertEquals(Priority.LOW, TaskQuickAdd.parsePriorityToken("!thấp"));
        assertNull(TaskQuickAdd.parsePriorityToken("! vừa"));
        assertEquals(Priority.MEDIUM, TaskQuickAdd.parse("Họp team").priority());
    }

    @Test
    void unknownTokensStayInTheTitle() {
        var p = TaskQuickAdd.parse("Gọi @nhà cung cấp lúc 10h");
        assertEquals("Gọi @nhà cung cấp lúc", p.title());
        assertEquals(LocalTime.of(10, 0), p.dueTime());
    }
}
