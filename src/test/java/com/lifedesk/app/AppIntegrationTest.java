package com.lifedesk.app;

import com.lifedesk.config.AppConfig;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class AppIntegrationTest {

    @TempDir
    Path dir;

    private AppContext context() {
        AppConfig config = new AppConfig();
        config.dataDir = dir.toString();
        config.remindersEnabled = false;
        return new AppContext(dir, config);
    }

    @Test
    void crossModuleSearchIndexWorksOffline() {
        AppContext ctx = context();
        ctx.tasks().create("Chuẩn bị thuyết trình", "khách hàng ABC", Priority.HIGH, LocalDate.now(), null, "work", null, null);
        ctx.notes().create("Ghi chú thuyết trình", "", "nội dung về khách hàng abc", null);
        ctx.search().rebuildAll();

        var groups = ctx.search().query("thuyet trinh", null, 10);
        assertTrue(groups.stream().anyMatch(g -> g.type().equals("TASK")));
        assertTrue(groups.stream().anyMatch(g -> g.type().equals("NOTE")));

        var onlyNotes = ctx.search().query("thuyet trinh", java.util.Set.of("NOTE"), 10);
        assertTrue(onlyNotes.stream().allMatch(g -> g.type().equals("NOTE")));
    }

    @Test
    void dataSurvivesContextRestart() {
        AppContext ctx = context();
        var t = ctx.tasks().create("Bền vững", "", Priority.MEDIUM, LocalDate.now(), LocalTime.of(9, 30), "grp", null, null);
        ctx.finance().addTransaction(LocalDate.now(), com.lifedesk.finance.TxType.EXPENSE,
                new BigDecimal("123.45"), ctx.finance().categories(com.lifedesk.finance.TxType.EXPENSE).get(0).id,
                com.lifedesk.finance.PaymentMethod.CASH, "mua vật liệu", null, null, false);
        ctx.shutdown();

        AppContext ctx2 = context();
        TaskStatus status = ctx2.tasks().get(t.id).status;
        assertEquals(TaskStatus.TODO, status);
        assertEquals(LocalTime.of(9, 30), ctx2.tasks().get(t.id).dueTime);
        assertEquals(new BigDecimal("123.45"), ctx2.finance().all().get(0).amount);
        ctx2.shutdown();
    }

    @Test
    void singleInstanceLockPreventsSecondWriter() {
        AppConfig config = new AppConfig();
        config.dataDir = dir.toString();
        try (SingleInstanceLock first = SingleInstanceLock.acquire(dir)) {
            assertThrows(RuntimeException.class, () -> SingleInstanceLock.acquire(dir));
        }
        // lock released -> can acquire again
        try (SingleInstanceLock again = SingleInstanceLock.acquire(dir)) {
            assertNotNull(again);
        }
    }

    @Test
    void ruleEngineAnswersFromRealData() {
        AppContext ctx = context();
        ctx.tasks().create("Việc rule engine thấy", "", Priority.HIGH, LocalDate.now(), null, "", null, null);
        var engine = ctx.ai().byId("rule-based");
        String reply = engine.chat(java.util.List.of(com.lifedesk.ai.ChatMessage.user("việc hôm nay")), "rule-engine-1.0");
        assertTrue(reply.contains("Việc rule engine thấy"));
    }

    @Test
    void reminderSchedulerFiresOncePerTaskPerDay() {
        AppConfig config = new AppConfig();
        config.dataDir = dir.resolve("reminder-data").toString();
        config.remindersEnabled = true;
        config.reminderMinutesBefore = 120;
        AppContext ctx = new AppContext(dir.resolve("reminder-data"), config);
        LocalTime soon = LocalTime.now().plusMinutes(30);
        if (soon.isBefore(LocalTime.now())) {
            soon = LocalTime.MAX.minusMinutes(1); // near-midnight edge; still in the future
        }
        var t = ctx.tasks().create("Sắp đến hạn", "", Priority.HIGH, LocalDate.now(), soon, "", null, null);

        ReminderScheduler scheduler = ctx.reminders();
        scheduler.check();
        assertTrue(scheduler.wasSent("task-due-" + t.id + "-" + LocalDate.now()),
                "reminder must fire when due time is within the window");
        ctx.shutdown();

        // reload: dedupe keys persisted -> no re-notification
        AppContext ctx2 = new AppContext(dir.resolve("reminder-data"), config);
        assertTrue(ctx2.reminders().wasSent("task-due-" + t.id + "-" + LocalDate.now()),
                "sent-reminder keys must survive restart (no re-spam)");
        ctx2.shutdown();
    }
}
