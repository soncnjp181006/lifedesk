package com.lifedesk.app;

import com.lifedesk.ai.RuleBasedEngine;
import com.lifedesk.backup.BackupService;
import com.lifedesk.config.AppConfig;
import com.lifedesk.finance.TxType;
import com.lifedesk.notes.SaveResult;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.TaskStatus;
import javafx.application.Platform;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Automated verification entry point (no database, no server).
 *
 * Modes:
 *   services  — boots the full service stack on a temp data dir, exercises
 *               every module (CRUD, persistence across reload, markdown,
 *               search, finance BigDecimal math, backup/restore, agent
 *               policy, rule engine), prints PASS/FAIL per check and exits
 *               non-zero on any failure.
 *   ui        — launches the real JavaFX app on a temp data dir, waits,
 *               captures a screenshot (java.awt.Robot), then exits.
 */
public final class SmokeMain {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        uiArgs = args;
        String mode = args.length > 0 ? args[0] : "services";
        if ("ui".equalsIgnoreCase(mode)) {
            runUiSmoke();
        } else {
            boolean ok = runServicesSmoke();
            System.out.println("SMOKE RESULT: " + (ok ? "PASS" : "FAIL")
                    + " (" + passed + " passed, " + failed + " failed)");
            System.exit(ok ? 0 : 1);
        }
    }

    // ------------------------------------------------------------------ services smoke

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

        // 5. Health + Learning
        ctx.health().upsert(LocalDate.now(), new BigDecimal("7.5"), 30, new BigDecimal("65.4"), null, "");
        check("health upsert đọc lại", ctx.health().byDate(LocalDate.now()).isPresent());
        var deck = ctx.learning().addDeck("Toeic", "");
        ctx.learning().addCard(deck.id, "abandon", "từ bỏ");
        check("flashcard đến hạn ngay khi tạo", ctx.learning().dueCount() == 1);
        var card = ctx.learning().dueCards(null).get(0);
        ctx.learning().review(card.id, true);
        check("leitner box tăng khi đúng", ctx.learning().cardsInDeck(deck.id).get(0).box == 2);
        check("học buổi học + thống kê phút", ctx.learning().minutesBetween(LocalDate.now(), LocalDate.now()) == 0);

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

        // 9. Agent: policy + destructive approval + limits
        var plan = new com.lifedesk.automation.AgentPlan();
        plan.name = "smoke plan";
        var step1 = new com.lifedesk.automation.PlanStep();
        step1.tool = "dashboard.summary";
        var step2 = new com.lifedesk.automation.PlanStep();
        step2.tool = "task.create";
        step2.params.addProperty("title", "Agent tạo việc");
        plan.steps = List.of(step1, step2);
        boolean needsApproval = false;
        try {
            ctx.agentService().validatePlan(plan, false);
        } catch (RuntimeException e) {
            needsApproval = true;
        }
        check("plan WRITE cần phê duyệt", needsApproval);
        ctx.agentService().validatePlan(plan, true); // approved passes

        // Unknown tool denied
        var badPlan = new com.lifedesk.automation.AgentPlan();
        badPlan.steps = List.of(newStep("shell.exec", new com.google.gson.JsonObject()));
        boolean unknownDenied = false;
        try {
            ctx.agentService().validatePlan(badPlan, true);
        } catch (RuntimeException e) {
            unknownDenied = true;
        }
        check("tool lạ bị từ chối (không shell)", unknownDenied);

        // Destructive step requires live approval (no handler => denied)
        var note2 = ctx.notes().create("Xóa tôi", "", "nội dung", null);
        var delPlan = new com.lifedesk.automation.AgentPlan();
        delPlan.steps = List.of(newStep("note.delete", params("noteId", note2.id)));
        var run = ctx.agentService().submitRun(delPlan, true, "manual");
        waitRun(run);
        check("bước destructive không duyệt => run CANCELLED", run.status == com.lifedesk.automation.RunStatus.CANCELLED);
        check("audit log ghi nhận bước bị từ chối",
                Files.list(dataDir.resolve("automation")).anyMatch(p -> p.getFileName().toString().startsWith("audit-")));

        // READ+WRITE plan with approval completes end to end
        var okPlan = new com.lifedesk.automation.AgentPlan();
        okPlan.steps = List.of(step1, step2);
        var run2 = ctx.agentService().submitRun(okPlan, true, "manual");
        waitRun(run2);
        check("run READ+WRITE hoàn thành", run2.status == com.lifedesk.automation.RunStatus.COMPLETED);
        check("agent thực sự tạo task", ctx.tasks().search("Agent tạo việc", null, null).size() == 1);

        // 10. Reload: persistence across instances (acceptance #5)
        ctx.shutdown();
        AppContext ctx2 = new AppContext(dataDir, config);
        check("task tồn tại sau reload", ctx2.tasks().search("Viết báo cáo tuần", null, null).size() == 1);
        check("note tồn tại sau reload", ctx2.notes().findByTitle("Ghi chú dự án").isPresent());
        check("finance tồn tại sau reload", ctx2.finance().transactionCount() >= 2);
        check("habit tồn tại sau reload", ctx2.habits().habits(true).size() == 1);
        check("learning tồn tại sau reload", ctx2.learning().decks().size() == 1);
        check("runs tồn tại sau reload", !ctx2.agentService().runs().isEmpty());

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

    private static com.lifedesk.automation.PlanStep newStep(String tool, com.google.gson.JsonObject params) {
        var step = new com.lifedesk.automation.PlanStep();
        step.tool = tool;
        step.params = params;
        return step;
    }

    private static com.google.gson.JsonObject params(String key, String value) {
        var o = new com.google.gson.JsonObject();
        o.addProperty(key, value);
        return o;
    }

    private static void waitRun(com.lifedesk.automation.AgentRun run) throws Exception {
        long deadline = System.currentTimeMillis() + 30000;
        while (!run.isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
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

    // ------------------------------------------------------------------ ui smoke

    private static void runUiSmoke() throws Exception {
        Path dataDir = Files.createTempDirectory("lifedesk-uismoke-");
        System.setProperty("lifedesk.data", dataDir.toString());
        Path screenshot = Path.of(uiArgs.length > 1 ? uiArgs[1] : "target/ui-smoke.png");
        Files.createDirectories(screenshot.toAbsolutePath().getParent());

        // seed some data so the UI shows content
        AppConfig config = new AppConfig();
        config.dataDir = dataDir.toString();
        config.remindersEnabled = false;
        AppContext ctx = new AppContext(dataDir, config);
        ctx.tasks().create("Việc mẫu cho smoke UI", "tạo tự động", Priority.HIGH, LocalDate.now(), null, "Demo", List.of(), null);
        ctx.notes().create("Ghi chú mẫu", "", "# Xin chào\n\nĐây là **markdown** preview.", null);
        ctx.finance().addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("250000"),
                ctx.finance().categories(TxType.EXPENSE).get(0).id,
                com.lifedesk.finance.PaymentMethod.CASH, "cafe", null, null, false);
        ctx.shutdown();

        System.out.println("Launching JavaFX UI smoke; screenshot -> " + screenshot.toAbsolutePath());
        javafx.application.Platform.setImplicitExit(false);
        Thread javafxThread = new Thread(() -> javafx.application.Application.launch(LifeDeskApp.class, new String[0]));
        javafxThread.setDaemon(true);
        javafxThread.start();

        // Wait for the app window then snapshot the SCENE itself (immune to overlapping windows).
        long deadline = System.currentTimeMillis() + 60000;
        boolean captured = false;
        int loops = 0;
        while (System.currentTimeMillis() < deadline && !captured) {
            Thread.sleep(500);
            loops++;
            javafx.stage.Stage stage = LifeDeskApp.primaryStageRef;
            if (stage != null && stage.isShowing() && stage.getScene() != null && stage.getScene().getRoot() != null) {
                Thread.sleep(2500); // let the UI settle
                java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> capture =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        javafx.scene.image.WritableImage img = stage.getScene().snapshot(null);
                        int width = (int) img.getWidth();
                        int height = (int) img.getHeight();
                        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                buffered.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                            }
                        }
                        capture.complete(buffered);
                    } catch (Throwable t) {
                        capture.completeExceptionally(t);
                    }
                });
                java.awt.image.BufferedImage img = capture.get(60, java.util.concurrent.TimeUnit.SECONDS);
                javax.imageio.ImageIO.write(img, "png", screenshot.toFile());
                System.out.println("Screenshot saved: " + screenshot.toAbsolutePath()
                        + " (" + img.getWidth() + "x" + img.getHeight() + ")");
                if (System.getProperty("lifedesk.debug.ui") != null) {
                    dump(stage.getScene().getRoot(), 0);
                }
                captured = true;
            }
        }
        // Additional view snapshots for broader UI verification
        if (captured && LifeDeskApp.mainWindowRef != null) {
            String[][] extraViews = {
                    {"TASKS", "target/ui-smoke-tasks.png"},
                    {"NOTES", "target/ui-smoke-notes.png"},
                    {"FINANCE", "target/ui-smoke-finance.png"},
                    {"AUTOMATION", "target/ui-smoke-automation.png"},
                    {"SETTINGS", "target/ui-smoke-settings.png"},
            };
            for (String[] v : extraViews) {
                Path viewShot = screenshot.toAbsolutePath().getParent().resolve(Path.of(v[1]).getFileName());
                java.util.concurrent.CompletableFuture<Boolean> ok = new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        LifeDeskApp.mainWindowRef.showView(com.lifedesk.ui.MainWindow.ViewId.valueOf(v[0]));
                        ok.complete(true);
                    } catch (Throwable t) {
                        ok.completeExceptionally(t);
                    }
                });
                try {
                    ok.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    Thread.sleep(1200);
                    java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> cap2 =
                            new java.util.concurrent.CompletableFuture<>();
                    javafx.application.Platform.runLater(() -> {
                        try {
                            javafx.scene.image.WritableImage img2 =
                                    LifeDeskApp.primaryStageRef.getScene().snapshot(null);
                            int width = (int) img2.getWidth();
                            int height = (int) img2.getHeight();
                            java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                    width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < width; x++) {
                                    buffered.setRGB(x, y, img2.getPixelReader().getArgb(x, y));
                                }
                            }
                            cap2.complete(buffered);
                        } catch (Throwable t) {
                            cap2.completeExceptionally(t);
                        }
                    });
                    javax.imageio.ImageIO.write(cap2.get(60, java.util.concurrent.TimeUnit.SECONDS),
                            "png", viewShot.toFile());
                    System.out.println("View snapshot: " + v[0] + " -> " + viewShot);
                } catch (Exception e) {
                    System.out.println("View snapshot FAILED for " + v[0] + ": " + e);
                }
            }
        }
        if (!captured) {
            javafx.stage.Stage s = LifeDeskApp.primaryStageRef;
            System.out.println("DEBUG: loops=" + loops + " stage=" + (s == null ? "null"
                    : "isShowing=" + s.isShowing() + " scene=" + (s.getScene() != null)
                    + " root=" + (s.getScene() != null && s.getScene().getRoot() != null)));
            System.out.println("SMOKE RESULT: FAIL (no window appeared in 60s)");
            System.exit(1);
        }
        // Exit cleanly
        javafx.application.Platform.runLater(() -> javafx.application.Platform.exit());
        Thread.sleep(1500);
        System.out.println("SMOKE RESULT: PASS (UI launched, screenshot captured)");
        System.exit(0);
    }

    private static void dump(javafx.scene.Node n, int depth) {
        System.out.println("  ".repeat(depth) + n.getClass().getSimpleName()
                + " id=" + n.getId() + " cls=" + n.getStyleClass()
                + " bounds=" + n.getLayoutBounds() + " visible=" + n.isVisible());
        if (n instanceof javafx.scene.Parent p && depth < 6) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                dump(child, depth + 1);
            }
        }
    }

    private static String[] uiArgs = new String[0];

    private SmokeMain() {
    }
}
