package com.lifedesk.app;

import com.lifedesk.ai.RuleBasedEngine;
import com.lifedesk.backup.BackupService;
import com.lifedesk.config.AppConfig;
import com.lifedesk.finance.TxType;
import com.lifedesk.notes.SaveResult;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.TaskStatus;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** Automated service-stack verification (the JavaFX UI smoke was removed with the old UI). */
public final class SmokeMain {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("lifedesk.smoke", "true");
        boolean ok = runServicesSmoke();
        System.out.println("SMOKE RESULT: " + (ok ? "PASS" : "FAIL")
                + " (" + passed + " passed, " + failed + " failed)");
        System.exit(ok ? 0 : 1);
    }

    static boolean runServicesSmoke() throws Exception {
        Path dataDir = Files.createTempDirectory("lifedesk-smoke-");
        System.out.println("== LifeDesk services smoke on " + dataDir);

        AppConfig config = new AppConfig();
        config.dataDir = dataDir.toString();
        config.remindersEnabled = false;
        AppContext ctx = new AppContext(dataDir, config);

        // 1. Task CRUD + persistence + recurrence
        var t1 = ctx.tasks().create("Viết báo cáo tuần", "bao gồm KPI", Priority.HIGH,
                LocalDate.now(), null, "Công việc", List.of("work"), null);
        var t2 = ctx.tasks().create("Họp team", "", Priority.MEDIUM, LocalDate.now().plusDays(1), null, "", List.of(), null);
        ctx.tasks().setStatus(t2.id, TaskStatus.IN_PROGRESS);
        check("task.search tìm được", !ctx.tasks().search("báo cáo", null, null).isEmpty());
        check("task.overdue rỗng khi mới tạo", ctx.tasks().overdue().isEmpty());

        // Recurrence: complete a DAILY task -> next occurrence spawns
        var rec = ctx.tasks().create("Tập thể dục", null, Priority.LOW, LocalDate.now(), null, "Sức khỏe", List.of(), null);
        rec.recurrence.type = com.lifedesk.tasks.RecurrenceType.DAILY;
        ctx.tasks().update(rec);
        ctx.tasks().setStatus(rec.id, TaskStatus.DONE);
        boolean spawned = ctx.tasks().all().stream()
                .anyMatch(t -> t.title.equals("Tập thể dục") && t.status == TaskStatus.TODO
                        && t.dueDate.isAfter(LocalDate.now()));
        check("công việc lặp lại sinh phiên mới", spawned);

        // 2. Notes: create/read/save/conflict
        var note = ctx.notes().create("Ghi chú dự án", "work", "# Dự án\n\n- [ ] phân tích\n- [x] setup\n\n[[Ghi chú khác]]",
                java.util.Set.of("work"));
        check("notes đọc lại nội dung", ctx.notes().readContent(note.id).contains("Dự án"));
        SaveResult saved = ctx.notes().saveContent(note.id, "# Dự án v2\n\nnội dung mới", null);
        check("notes save SAVED", saved == SaveResult.SAVED || saved == SaveResult.UNCHANGED);
        check("notes content persisted", ctx.notes().readContent(note.id).contains("v2"));

        // 3. Finance: BigDecimal math + dedupe + CSV
        var cat = ctx.finance().categories(TxType.EXPENSE).get(0);
        ctx.finance().addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("1234567.89"),
                cat.id, com.lifedesk.finance.PaymentMethod.CASH, "lunch", null, "ext-1", false);
        boolean dupRejected = false;
        try {
            ctx.finance().addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("1234567.89"),
                    cat.id, com.lifedesk.finance.PaymentMethod.CASH, "lunch", null, "ext-1", false);
        } catch (RuntimeException e) {
            dupRejected = true;
        }
        check("giao dịch trùng bị từ chối", dupRejected);
        ctx.finance().addTransaction(LocalDate.now(), TxType.INCOME, new BigDecimal("10000000"),
                ctx.finance().categories(TxType.INCOME).get(0).id,
                com.lifedesk.finance.PaymentMethod.BANK_TRANSFER, "salary", null, null, false);
        var summary = ctx.finance().monthSummary(java.time.YearMonth.now());
        check("BigDecimal tổng chính xác",
                summary.expense().compareTo(new BigDecimal("1234567.89")) == 0
                        && summary.income().compareTo(new BigDecimal("10000000")) == 0);
        Path csv = dataDir.resolve("smoke-export.csv");
        ctx.finance().exportCsv(csv, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));
        // Re-import the same file: all rows must be skipped as duplicates
        var report = ctx.finance().importCsv(csv);
        check("CSV import lại không nhân đôi", report.created == 0 && report.skippedDuplicates >= 2);

        // 4. Habits: streak + retroactive adjustment requires reason
        var habit = ctx.habits().addHabit("Đọc sách", null, null, "accent");
        ctx.habits().setCompletion(habit.id, LocalDate.now(), true, null, "user");
        check("streak = 1 sau khi hoàn thành hôm nay", ctx.habits().currentStreak(habit) == 1);
        boolean needReason = false;
        try {
            ctx.habits().setCompletion(habit.id, LocalDate.now().minusDays(2), true, null, "user");
        } catch (RuntimeException e) {
            needReason = true;
        }
        check("điều chỉnh lịch sử yêu cầu lý do", needReason);
        ctx.habits().setCompletion(habit.id, LocalDate.now().minusDays(2), true, "bù lịch sử", "user");
        check("nhật ký thay đổi ghi nhận điều chỉnh",
                ctx.habits().changeLog().stream().anyMatch(e -> e.reason.equals("bù lịch sử")));

        // 5. Health
        ctx.health().upsert(LocalDate.now(), new BigDecimal("7.5"), 30, new BigDecimal("65.4"), null, "");
        check("health upsert đọc lại", ctx.health().byDate(LocalDate.now()).isPresent());

        // 6. Rule engine (offline intelligence)
        RuleBasedEngine engine = ctx.ai().byId(RuleBasedEngine.ID) instanceof RuleBasedEngine r ? r : null;
        check("rule engine available", engine != null && engine.isAvailable());
        String today = engine.handle("việc hôm nay");
        check("rule engine trả lời việc hôm nay", today.contains("Viết báo cáo tuần"));
        String finance = engine.handle("chi tiêu tháng này");
        check("rule engine tổng hợp tài chính", finance.contains("Chi tiêu"));
        String unknown = engine.handle("thời tiết ngày mai thế nào nhỉ");
        check("rule engine trung thực khi không hiểu", unknown.contains("ngoài khả năng"));

        // 7. Markdown sanitize
        String html = ctx.markdownService().toHtml("# Hello\n\n<script>alert('xss')</script>\n\n| a | b |\n|---|---|\n| 1 | 2 |");
        check("markdown escape raw html (không thực thi script)", !html.contains("<script>"));
        check("markdown render bảng", html.contains("<table"));

        // 8. Search
        ctx.search().rebuildAll();
        var groups = ctx.search().query("báo cáo", null, 10);
        check("search xuyên module tìm task", groups.stream().anyMatch(g -> g.type().equals("TASK")));
        check("search index lưu trên đĩa", Files.exists(dataDir.resolve("index/search-index.json")));

        // 10. Reload: persistence across instances (acceptance #5)
        ctx.shutdown();
        AppContext ctx2 = new AppContext(dataDir, config);
        check("task tồn tại sau reload", ctx2.tasks().search("Viết báo cáo tuần", null, null).size() == 1);
        check("note tồn tại sau reload", ctx2.notes().findByTitle("Ghi chú dự án").isPresent());
        check("finance tồn tại sau reload", ctx2.finance().transactionCount() >= 2);
        check("habit tồn tại sau reload", ctx2.habits().habits(true).size() == 1);

        // 11. Backup + restore roundtrip (acceptance #10)
        BackupService backup = new BackupService();
        Path zip = backup.backup(dataDir, dataDir.resolve("backups"));
        var verify = backup.verify(zip);
        check("backup verify OK", verify.ok());
        // corrupt a store file, restore must fix it
        Files.writeString(dataDir.resolve("tasks/tasks.json"), "{corrupted json!!");
        var restored = backup.restore(zip, dataDir);
        check("restore khôi phục file hỏng", restored.problems().isEmpty() && restored.restored() > 0);
        check("integrityCheck sạch sau restore", backup.integrityCheck(dataDir).isEmpty());

        check("data/ai snapshot được ghi", Files.exists(dataDir.resolve("ai/ai-config.json")));

        // 12. Reminder scheduler logic
        ctx2.shutdown();
        System.out.println("== Services smoke done");
        return failed == 0;
    }



    static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ✓ " + name);
        } else {
            failed++;
            System.out.println("  ✗ FAIL: " + name);
        }
    }
}
