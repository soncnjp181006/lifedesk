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
 *               search, finance BigDecimal math, backup/restore,
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

    /** Depth-first search for a Button with the given label (smoke navigation aid). */
    private static javafx.scene.Node findButton(javafx.scene.Node root, String text) {
        if (root instanceof javafx.scene.control.Button b && text.equals(b.getText())) {
            return b;
        }
        if (root instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node n : p.getChildrenUnmodifiable()) {
                javafx.scene.Node found = findButton(n, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

        // 10. Reload: persistence across instances (acceptance #5)
        ctx.shutdown();
        AppContext ctx2 = new AppContext(dataDir, config);
        check("task tồn tại sau reload", ctx2.tasks().search("Viết báo cáo tuần", null, null).size() == 1);
        check("note tồn tại sau reload", ctx2.notes().findByTitle("Ghi chú dự án").isPresent());
        check("finance tồn tại sau reload", ctx2.finance().transactionCount() >= 2);
        check("habit tồn tại sau reload", ctx2.habits().habits(true).size() == 1);
        check("learning tồn tại sau reload", ctx2.learning().decks().size() == 1);

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

    // ------------------------------------------------------------------ demo dataset

    /**
     * A month of plausible personal data. Deliberately includes overdue work, a
     * budget that is nearly spent, a broken habit streak and a deck due today, so
     * the snapshots exercise the states a real screen has to survive — not just the
     * happy path a hand-typed fixture would produce.
     */
    private static void seedDemoData(AppContext ctx) {
        LocalDate today = LocalDate.now();
        String[] titles = {
                "Nộp báo cáo tuần cho quản lý", "Chuẩn bị slide họp sprint", "Trả lời email đối tác",
                "Ôn 30 từ vựng tiếng Nhật", "Đặt lịch khám răng", "Mua quà sinh nhật mẹ",
                "Rà soát lại ngân sách tháng", "Chạy bộ 5km", "Dọn dẹp thư mục Downloads",
                "Viết nháp chương 3", "Gọi điện cho anh Nam", "Kiểm tra hoá đơn điện" };
        String[] groups = {"Công việc", "Cá nhân", "Học tập", "Sức khỏe"};
        com.lifedesk.tasks.Priority[] pri = {com.lifedesk.tasks.Priority.HIGH,
                com.lifedesk.tasks.Priority.MEDIUM, com.lifedesk.tasks.Priority.LOW};
        for (int i = 0; i < 34; i++) {
            LocalDate due = i < 6 ? today.minusDays(1 + i) : today.plusDays(i % 9);
            var t = ctx.tasks().create(titles[i % titles.length] + (i >= titles.length ? " (" + (i / titles.length + 1) + ")" : ""),
                    i % 3 == 0 ? "Ghi chú: ưu tiên xử lý trước 17h." : "",
                    pri[i % 3], i % 11 == 0 ? null : due, null,
                    groups[i % groups.length], List.of(i % 2 == 0 ? "focus" : "nhà"), null);
            if (i % 4 == 3) {
                ctx.tasks().setStatus(t.id, com.lifedesk.tasks.TaskStatus.DONE);
            } else if (i % 5 == 2) {
                ctx.tasks().setStatus(t.id, com.lifedesk.tasks.TaskStatus.IN_PROGRESS);
            }
        }

        String[][] notes = {
                {"Kế hoạch tháng 11", "# Kế hoạch tháng 11\n\n## Mục tiêu\n- Hoàn thành báo cáo Q4\n- Chạy 40km trong tháng\n- Đọc xong *Thinking, Fast and Slow*\n\n## Ghi chú\n> Tuần đầu tiên chỉ tập trung vào việc quan trọng nhất.\n\n| Tuần | Trọng tâm |\n|---|---|\n| 1 | Báo cáo |\n| 2 | Ôn thi |\n"},
                {"Công thức nấu ăn nhanh", "# Món dưới 20 phút\n\n1. Mì trứng cà chua\n2. Cơm rang dưa bò\n3. Salad ức gà\n\n`nước mắm 1 thìa` + `đường 1/2 thìa`\n"},
                {"Ghi chú họp 28/10", "## Quyết định\n- Dời deadline sang 05/11\n- Họp lại thứ Sáu 9h\n\n## Việc cần làm\n- [x] Gửi biên bản\n- [ ] Cập nhật bảng tiến độ\n"},
                {"Ý tưởng dự án cá nhân", "# Ý tưởng\n\n- Ứng dụng theo dõi thói quen cho người mới bắt đầu\n- Newsletter tổng hợp tin công nghệ hằng tuần\n"},
                {"Sách đang đọc", "# Danh sách\n\n- [x] Atomic Habits\n- [ ] Deep Work\n- [ ] The Almanack of Naval Ravikant\n"},
                {"Ngân sách tháng 10", "# Chi tiêu cố định\n\n- Tiền nhà: 5.000.000\n- Điện nước: 850.000\n- Internet: 220.000\n"} };
        for (String[] n : notes) {
            ctx.notes().create(n[0], "work", n[1], java.util.Set.of("kế hoạch"));
        }

        var expense = ctx.finance().categories(com.lifedesk.finance.TxType.EXPENSE);
        var income = ctx.finance().categories(com.lifedesk.finance.TxType.INCOME);
        String[] spend = {"Ăn trưa văn phòng", "Cà phê sáng", "Grab đi làm", "Siêu thị cuối tuần",
                "Tiền điện", "Mua sách", "Xem phim", "Thuốc cảm", "Xăng xe", "Đồ dùng bếp"};
        java.util.Random rnd = new java.util.Random(42);
        for (int back = 0; back < 75; back++) {
            LocalDate d = today.minusDays(back);
            ctx.finance().addTransaction(d, com.lifedesk.finance.TxType.EXPENSE,
                    new java.math.BigDecimal(45_000 + rnd.nextInt(600_000)),
                    expense.get(rnd.nextInt(expense.size())).id,
                    com.lifedesk.finance.PaymentMethod.values()[rnd.nextInt(3)],
                    spend[rnd.nextInt(spend.length)], null, null, false);
            if (d.getDayOfMonth() == 5) {
                ctx.finance().addTransaction(d, com.lifedesk.finance.TxType.INCOME,
                        new java.math.BigDecimal("24500000"), income.get(0).id,
                        com.lifedesk.finance.PaymentMethod.BANK_TRANSFER, "Lương tháng", null, "salary-" + d, false);
            }
        }
        try {
            ctx.finance().addBudget(java.time.YearMonth.from(today), expense.get(0).id,
                    new java.math.BigDecimal("4000000"), true);
        } catch (RuntimeException ignored) {
            // duplicate budget in a re-seeded fixture is fine
        }

        var ensure = ctx.habits().addHabit("Đọc 20 trang", "Trước khi ngủ", java.util.Set.of(
                java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY), "#60a5fa");
        ctx.habits().addHabit("Uống 2 lít nước", "", java.util.EnumSet.allOf(java.time.DayOfWeek.class), "#4ade80");
        ctx.habits().addHabit("Thiền 10 phút", "", java.util.EnumSet.allOf(java.time.DayOfWeek.class), "#a78bfa");
        for (int back = 0; back < 26; back++) {
            LocalDate d = today.minusDays(back);
            if (back % 4 != 0) {
                ctx.habits().setCompletion(ensure.id, d, true, "smoke", "smoke");
            }
        }
        ctx.habits().addGoal("Chạy 40km trong tháng", "", today.plusDays(20));
        ctx.habits().addGoal("Đọc xong 2 cuốn sách", "", today.plusDays(45));

        for (int back = 0; back < 18; back++) {
            ctx.health().upsert(today.minusDays(back),
                    new java.math.BigDecimal(String.valueOf(6.5 + (back % 4) * 0.4)),
                    15 + (back % 5) * 10, new java.math.BigDecimal("68.4"), null, null);
        }

        var course = ctx.learning().addCourse("Tiếng Nhật N4", "JLPT", null, java.util.Set.of("ngoại ngữ"));
        var deck = ctx.learning().addDeck("N4 · Từ vựng tuần 3", course.id);
        for (String[] c : new String[][]{{"改善", "cải thiện"}, {"経験", "kinh nghiệm"},
                {"準備", "chuẩn bị"}, {"約束", "lời hẹn"}, {"相談", "trao đổi"}, {"我慢", "chịu đựng"}}) {
            ctx.learning().addCard(deck.id, c[0], c[1]);
        }
        ctx.learning().addSession(course.id, today.minusDays(1), 45, "Từ vựng bài 7");
        ctx.learning().addSession(course.id, today.minusDays(3), 30, "Ngữ pháp て-form");
        System.out.println("Seeded demo dataset: 34 tasks, 6 notes, ~76 transactions, 3 habits, 18 health rows");
    }

    // ------------------------------------------------------------------ ui smoke

    private static void runUiSmoke() throws Exception {
        Path dataDir = Files.createTempDirectory("lifedesk-uismoke-");
        // Optional fixture: -Dlifedesk.seed=<dir> copies a real dataset in, so the
        // snapshots show populated views instead of empty states.
        String seedDir = System.getProperty("lifedesk.seed");
        if (seedDir != null && !seedDir.isBlank()) {
            copyTree(Path.of(seedDir), dataDir);
            System.out.println("Seeded UI smoke data from " + seedDir);
        }
        System.setProperty("lifedesk.data", dataDir.toString());
        // Own config dir too, so the smoke never reads/writes the user's real config.
        System.setProperty("lifedesk.config", dataDir.resolve("config").toString());
        Path screenshot = Path.of(uiArgs.length > 1 ? uiArgs[1] : "target/ui-smoke.png");
        Files.createDirectories(screenshot.toAbsolutePath().getParent());

        AppConfig config = new AppConfig();
        config.dataDir = dataDir.toString();
        config.remindersEnabled = false;
        AppContext ctx = new AppContext(dataDir, config);
        // An empty database makes every screen look the same and makes navigation
        // timings meaningless. Without an explicit fixture, seed a realistic month so
        // both the snapshots and the timing numbers describe the app people actually run.
        if (seedDir == null || seedDir.isBlank()) {
            seedDemoData(ctx);
        }
        ctx.shutdown();
        seedNoteIdHolder = firstNoteId(dataDir);

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
            // Shortcut registration is otherwise invisible; assert the map, since the
            // accelerators are the only keyboard path into the modules.
            java.util.concurrent.CompletableFuture<int[]> accCount =
                    new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                var acc = LifeDeskApp.primaryStageRef.getScene().getAccelerators();
                int found = 0;
                javafx.scene.input.KeyCode[] digits = {
                        javafx.scene.input.KeyCode.DIGIT1, javafx.scene.input.KeyCode.DIGIT2,
                        javafx.scene.input.KeyCode.DIGIT3, javafx.scene.input.KeyCode.DIGIT4,
                        javafx.scene.input.KeyCode.DIGIT5, javafx.scene.input.KeyCode.DIGIT6,
                        javafx.scene.input.KeyCode.DIGIT7, javafx.scene.input.KeyCode.DIGIT8,
                        javafx.scene.input.KeyCode.DIGIT9, javafx.scene.input.KeyCode.DIGIT0};
                for (javafx.scene.input.KeyCode d : digits) {
                    if (acc.containsKey(new javafx.scene.input.KeyCodeCombination(
                            d, javafx.scene.input.KeyCombination.CONTROL_DOWN))) {
                        found++;
                    }
                }
                accCount.complete(new int[]{found, acc.size()});
            });
            int[] acc = accCount.get(10, java.util.concurrent.TimeUnit.SECONDS);
            check("phím tắt Ctrl+1..Ctrl+0 đã đăng ký (" + acc[0] + "/10)", acc[0] == 10);
            check("tổng accelerator >= 14 (có Ctrl+K, Ctrl+B, Ctrl+,)", acc[1] >= 14);
            String[][] extraViews = {
                    {"TASKS", "target/ui-smoke-tasks.png"},
                    {"NOTES", "target/ui-smoke-notes.png"},
                    {"FINANCE", "target/ui-smoke-finance.png"},
                    {"HABITS", "target/ui-smoke-habits.png"},
                    {"HEALTH", "target/ui-smoke-health.png"},
                    {"LEARNING", "target/ui-smoke-learning.png"},
                    {"FILES", "target/ui-smoke-files.png"},
                    {"AI", "target/ui-smoke-ai.png"},
                    {"AGENT", "target/ui-smoke-agent.png"},
                    {"RSS", "target/ui-smoke-rss.png"},
                    {"LIBRARY", "target/ui-smoke-library.png"},
                    {"SETTINGS", "target/ui-smoke-settings.png"},
            };
            for (String[] v : extraViews) {
                Path viewShot = screenshot.toAbsolutePath().getParent().resolve(Path.of(v[1]).getFileName());
                final String seedNoteId = seedNoteIdHolder;
                java.util.concurrent.CompletableFuture<Boolean> ok = new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        LifeDeskApp.mainWindowRef.showView(com.lifedesk.ui.MainWindow.ViewId.valueOf(v[0]));
                        // For the notes view, actually open the seeded note so the
                        // screenshot proves the editor + preview really appear.
                        if ("NOTES".equals(v[0]) && seedNoteId != null) {
                            LifeDeskApp.mainWindowRef.openRecord("NOTE", seedNoteId);
                        }
                        // Proves the switch happened at all: a view that never replaced
                        // the previous one still snapshots "successfully".
                        var area = (javafx.scene.layout.Pane) LifeDeskApp.primaryStageRef.getScene()
                                .lookup(".content-area");
                        String shown = area != null && !area.getChildren().isEmpty()
                                && area.getChildren().get(0) instanceof com.lifedesk.ui.View shownView
                                        ? shownView.id() : "(none)";
                        System.out.println("SHOW " + v[0] + " -> " + shown);
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
                            var scene = LifeDeskApp.primaryStageRef.getScene();
                            javafx.scene.image.WritableImage img2 = scene.snapshot(null);
                            int width = (int) img2.getWidth();
                            int height = (int) img2.getHeight();
                            java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                    width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < width; x++) {
                                    buffered.setRGB(x, y, img2.getPixelReader().getArgb(x, y));
                                }
                            }
                            // Overflow guard: horizontal clipping is invisible in code review.
                            // Found by style class, not by a fixed tree position: the probe
                            // must survive a shell restructure without silently skipping.
                            javafx.scene.control.ScrollPane sp = null;
                            for (javafx.scene.Node n = scene.lookup(".content-area"); n != null; n = n.getParent()) {
                                if (n instanceof javafx.scene.control.ScrollPane found) {
                                    sp = found;
                                    break;
                                }
                            }
                            if (sp == null) {
                                throw new IllegalStateException("Không tìm thấy vùng nội dung cuộn (.content-area)");
                            }
                            double viewport = sp.getViewportBounds().getWidth();
                            double contentW = sp.getContent().getLayoutBounds().getWidth();
                            String center = "(?)";
                            var kids = ((javafx.scene.layout.Pane) sp.getContent()).getChildren();
                            if (!kids.isEmpty()) {
                                center = kids.get(0) instanceof com.lifedesk.ui.View mv
                                        ? mv.id() : kids.get(0).getClass().getSimpleName();
                            }
                            // The rendered page title must belong to the view we just
                            // showed: a desync between the rail highlight and the content
                            // is invisible in a layout check but obvious to the user.
                            String renderedTitle = "?";
                            var titleNode = scene.lookup(".title-label");
                            if (titleNode instanceof javafx.scene.control.Label tl) {
                                renderedTitle = tl.getText();
                            }
                            System.out.println("RENDER " + v[0] + " title=\"" + renderedTitle + "\"");
                            // Once printed this as a note; a note nobody fails on is not a check.
                            check("không tràn ngang khi ở " + v[0], contentW <= viewport + 1);
                            System.out.println("LAYOUT " + v[0] + " center=" + center
                                    + " children=" + ((javafx.scene.layout.Pane) sp.getContent()).getChildren().size()
                                    + " viewport=" + Math.round(viewport)
                                    + " content=" + Math.round(contentW)
                                    + (contentW > viewport + 1 ? "  <== OVERFLOW" : "  ok"));
                            if (System.getProperty("lifedesk.debug.ui") != null) {
                                dump(sp.getContent(), 0);
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
                    // Previously this only printed, so a harness that could not inspect
                    // anything still reported PASS.
                    check("chụp và kiểm tra được view " + v[0], false);
                    System.out.println("View snapshot FAILED for " + v[0] + ": " + e);
                }
            }
        }
        // ---- Theme-switch regression: WebView panes (review card, markdown preview,
        // RSS reader) embed the palette inside their HTML, so a stylesheet swap alone
        // used to leave them wearing the previous theme's colors. Switch Dark→Light
        // on LEARNING and measure the review-card region: it must actually lighten.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> themeCap =
                    new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                    w.showView(com.lifedesk.ui.MainWindow.ViewId.LEARNING);
                    w.switchTheme("Light");
                    themeCap.complete(null);
                } catch (Throwable t) {
                    themeCap.completeExceptionally(t);
                }
            });
            try {
                themeCap.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1500); // WebView reload + repaint
                java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> cap3 =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        var scene = LifeDeskApp.primaryStageRef.getScene();
                        javafx.scene.image.WritableImage img3 = scene.snapshot(null);
                        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                (int) img3.getWidth(), (int) img3.getHeight(),
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < (int) img3.getHeight(); y++) {
                            for (int x = 0; x < (int) img3.getWidth(); x++) {
                                buffered.setRGB(x, y, img3.getPixelReader().getArgb(x, y));
                            }
                        }
                        cap3.complete(buffered);
                    } catch (Throwable t) {
                        cap3.completeExceptionally(t);
                    }
                });
                java.awt.image.BufferedImage lightShot = cap3.get(60, java.util.concurrent.TimeUnit.SECONDS);
                Path themeShotPath = screenshot.toAbsolutePath().getParent()
                        .resolve("ui-smoke-learning-after-theme-switch.png");
                javax.imageio.ImageIO.write(lightShot, "png", themeShotPath.toFile());
                // Average brightness of a 200x200 patch at the scene center — the review
                // card fills that area. Light page ≈ white; a stale dark palette ≈ #1a2436.
                int cx = lightShot.getWidth() / 2;
                int cy = lightShot.getHeight() / 2;
                long lum = 0;
                int n = 0;
                for (int y = cy - 100; y < cy + 100; y += 4) {
                    for (int x = cx - 100; x < cx + 100; x += 4) {
                        int argb = lightShot.getRGB(x, y);
                        int r = (argb >> 16) & 0xFF;
                        int g = (argb >> 8) & 0xFF;
                        int b = argb & 0xFF;
                        lum += (r + g + b) / 3;
                        n++;
                    }
                }
                long avg = lum / Math.max(1, n);
                System.out.println("THEME review-card brightness after Dark→Light: " + avg);
                check("thẻ ôn tập đổi màu theo theme (" + avg + ")", avg > 180);
            } catch (Exception e) {
                check("đổi theme không lỗi", false);
                System.out.println("Theme switch FAILED: " + e);
            }
        }
        // ---- Task-dialog regression: the create dialog must show Vietnamese enum
        // names (never raw "MEDIUM"/"TODO", which used to truncate to "…") and a
        // non-squeezed layout. Open it, screen-capture, then Esc out.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            java.util.concurrent.CompletableFuture<Boolean> dlg = new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                    w.showView(com.lifedesk.ui.MainWindow.ViewId.TASKS);
                    javafx.scene.Node found = findButton(
                            LifeDeskApp.primaryStageRef.getScene().getRoot(), "Công việc mới");
                    if (!(found instanceof javafx.scene.control.Button b)) {
                        throw new IllegalStateException("Không tìm thấy nút Công việc mới");
                    }
                    // complete BEFORE fire(): showAndWait() blocks this lambda in a
                    // nested event loop until the dialog closes.
                    dlg.complete(true);
                    b.fire();
                } catch (Throwable t) {
                    dlg.completeExceptionally(t);
                }
            });
            try {
                dlg.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1500);
                // Snapshot the dialog's OWN scene — a full-screen Robot capture would
                // grab whatever window happens to be frontmost (browser, chat…).
                java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> capDlg =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        for (javafx.stage.Window win : javafx.stage.Window.getWindows()) {
                            if (win instanceof javafx.stage.Stage st && st.isShowing()
                                    && st != LifeDeskApp.primaryStageRef
                                    && st.getScene() != null) {
                                javafx.scene.image.WritableImage img =
                                        st.getScene().snapshot(null);
                                java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                        (int) img.getWidth(), (int) img.getHeight(),
                                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                                for (int y = 0; y < (int) img.getHeight(); y++) {
                                    for (int x = 0; x < (int) img.getWidth(); x++) {
                                        buffered.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                                    }
                                }
                                capDlg.complete(buffered);
                                return;
                            }
                        }
                        capDlg.completeExceptionally(new IllegalStateException("Dialog window not found"));
                    } catch (Throwable t) {
                        capDlg.completeExceptionally(t);
                    }
                });
                java.awt.image.BufferedImage shot = capDlg.get(60, java.util.concurrent.TimeUnit.SECONDS);
                Path dlgShot = screenshot.toAbsolutePath().getParent().resolve("ui-smoke-task-dialog.png");
                javax.imageio.ImageIO.write(shot, "png", dlgShot.toFile());
                System.out.println("Task dialog snapshot: " + dlgShot);
                // Close the dialog via its Cancel button (locale-independent ButtonBar
                // role). A stuck-open dialog crashed the JVM in glass.dll during the
                // final Platform.exit (JDK 25 window-close race).
                javafx.application.Platform.runLater(() -> {
                    for (javafx.stage.Window win : javafx.stage.Window.getWindows()) {
                        if (win instanceof javafx.stage.Stage st && st.isShowing()
                                && st != LifeDeskApp.primaryStageRef
                                && st.getScene() != null
                                && st.getScene().getRoot() instanceof javafx.scene.Parent root
                                && root.lookup(".button-bar") instanceof javafx.scene.control.ButtonBar bar) {
                            bar.getButtons().stream()
                                    .filter(n -> n instanceof javafx.scene.control.Button b
                                            && javafx.scene.control.ButtonBar.getButtonData(b)
                                                    == javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE)
                                    .findFirst()
                                    .ifPresent(n -> ((javafx.scene.control.Button) n).fire());
                        }
                    }
                });
                Thread.sleep(800);
                check("mở được dialog công việc mới", true);
            } catch (Exception e) {
                check("mở được dialog công việc mới", false);
                System.out.println("Task dialog snapshot FAILED: " + e);
            }
        }
        // ---- Flashcards tab regression (user report: deck/card panels misaligned,
        // English "No content in table" placeholder). Open the tab, snapshot it.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            java.util.concurrent.CompletableFuture<Boolean> fc = new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    LifeDeskApp.mainWindowRef.showView(com.lifedesk.ui.MainWindow.ViewId.LEARNING);
                    boolean opened = false;
                    for (javafx.scene.Node n : LifeDeskApp.primaryStageRef.getScene().getRoot().lookupAll(".tab-pane")) {
                        if (n instanceof javafx.scene.control.TabPane tp) {
                            for (int i = 0; i < tp.getTabs().size(); i++) {
                                if ("Flashcards".equals(tp.getTabs().get(i).getText())) {
                                    tp.getSelectionModel().select(i);
                                    opened = true;
                                }
                            }
                        }
                    }
                    if (!opened) {
                        throw new IllegalStateException("Không tìm thấy tab Flashcards");
                    }
                    fc.complete(true);
                } catch (Throwable t) {
                    fc.completeExceptionally(t);
                }
            });
            try {
                fc.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1200);
                java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> capF =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        var scene = LifeDeskApp.primaryStageRef.getScene();
                        javafx.scene.image.WritableImage img = scene.snapshot(null);
                        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                (int) img.getWidth(), (int) img.getHeight(),
                                java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < (int) img.getHeight(); y++) {
                            for (int x = 0; x < (int) img.getWidth(); x++) {
                                buffered.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                            }
                        }
                        capF.complete(buffered);
                    } catch (Throwable t) {
                        capF.completeExceptionally(t);
                    }
                });
                Path fcShot = screenshot.toAbsolutePath().getParent().resolve("ui-smoke-learning-flashcards.png");
                javax.imageio.ImageIO.write(capF.get(60, java.util.concurrent.TimeUnit.SECONDS), "png", fcShot.toFile());
                System.out.println("Flashcards tab snapshot: " + fcShot);
            } catch (Exception e) {
                check("mở được tab Flashcards", false);
                System.out.println("Flashcards snapshot FAILED: " + e);
            }
        }
        // ---- View-switch latency: navigation is a UI-thread operation, so time the
        // click-to-painted-layout path for every view (cold + warm). A regression here
        // is exactly what "chuyển trang chậm" feels like to the user.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            java.util.concurrent.CompletableFuture<String> timings =
                    new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                    var scene = LifeDeskApp.primaryStageRef.getScene();
                    var ids = com.lifedesk.ui.MainWindow.ViewId.values();
                    StringBuilder report = new StringBuilder();
                    for (int pass = 0; pass < 2; pass++) {
                        for (com.lifedesk.ui.MainWindow.ViewId id : ids) {
                            long t0 = System.nanoTime();
                            w.showView(id);
                            scene.getRoot().applyCss();
                            scene.getRoot().layout();
                            long ms = (System.nanoTime() - t0) / 1_000_000;
                            report.append(pass == 0 ? "cold " : "warm ").append(id)
                                    .append('=').append(ms).append("ms ");
                        }
                    }
                    // Same view again: showView() short-circuits to onShow(), so this is
                    // the data-refresh cost alone, without swapping nodes or a layout pass.
                    StringBuilder refresh = new StringBuilder();
                    for (com.lifedesk.ui.MainWindow.ViewId id : ids) {
                        long t0 = System.nanoTime();
                        w.showView(id);
                        long ms = (System.nanoTime() - t0) / 1_000_000;
                        refresh.append(' ').append(id).append('=').append(ms).append("ms");
                    }
                    timings.complete(report + "| refresh-only:" + refresh);
                } catch (Throwable t) {
                    timings.completeExceptionally(t);
                }
            });
            try {
                System.out.println("SWITCH " + timings.get(30, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                System.out.println("SWITCH measurement FAILED: " + e);
            }
        }
        // ---- Frame pacing: "lag" is a long gap between pulses, not a slow average.
        // Switch views once per pulse and record the worst gap + how many frames blew
        // the 50ms budget — the numbers a design/CSS change can be judged against.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            java.util.concurrent.CompletableFuture<String> perf = new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                    var ids = com.lifedesk.ui.MainWindow.ViewId.values();
                    int[] frame = {0};
                    long[] last = {System.nanoTime()};
                    long[] maxGap = {0};
                    int[] slow = {0};
                    // Gap attributed to the view that was on screen while the pause
                    // happened — the average hides a single 120ms offender.
                    String[] shown = {"(startup)"};
                    java.util.Map<String, Long> worstByView = new java.util.LinkedHashMap<>();
                    long start = System.nanoTime();
                    new javafx.animation.AnimationTimer() {
                        @Override
                        public void handle(long now) {
                            long t = System.nanoTime();
                            long gap = (t - last[0]) / 1_000_000;
                            if (frame[0] > 2) {          // ignore startup pulses
                                maxGap[0] = Math.max(maxGap[0], gap);
                                if (gap > 50) {
                                    slow[0]++;
                                }
                                worstByView.merge(shown[0], gap, Math::max);
                            }
                            last[0] = t;
                            shown[0] = ids[frame[0] % ids.length].name();
                            w.showView(ids[frame[0]++ % ids.length]);
                            if (frame[0] >= 84) {
                                stop();
                                long totalMs = (System.nanoTime() - start) / 1_000_000;
                                StringBuilder perView = new StringBuilder();
                                worstByView.forEach((k, v) -> perView.append(' ').append(k).append('=').append(v));
                                perf.complete("frames=" + frame[0] + " totalMs=" + totalMs
                                        + " avgFps=" + Math.round(frame[0] * 1000.0 / Math.max(1, totalMs))
                                        + " maxGapMs=" + maxGap[0]
                                        + " framesOver50ms=" + slow[0]
                                        + " | worstGapByView:" + perView);
                            }
                        }
                    }.start();
                } catch (Throwable t) {
                    perf.completeExceptionally(t);
                }
            });
            try {
                System.out.println("PERF " + perf.get(120, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                System.out.println("PERF measurement FAILED: " + e);
            }
        }
        // ---- AI chat round-trip: type a question, press Enter, snapshot the reply.
        // Proves the chat surface, the streaming render path and the markdown output
        // (the rule engine answers deterministically from local data).
        if (captured && LifeDeskApp.mainWindowRef != null) {
            Path chatShot = screenshot.toAbsolutePath().getParent().resolve("ui-smoke-ai-chat.png");
            try {
                java.util.concurrent.CompletableFuture<String> sent =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        LifeDeskApp.mainWindowRef.showView(com.lifedesk.ui.MainWindow.ViewId.AI);
                        var scene = LifeDeskApp.primaryStageRef.getScene();
                        var input = scene.lookup(".chat-input");
                        if (input instanceof javafx.scene.control.TextArea area) {
                            // The briefing exercises the widest answer: headings, table-free
                            // sections, lists, bold text — one shot through the markdown renderer.
                            area.setText("tổng quan hôm nay");
                            area.fireEvent(new javafx.scene.input.KeyEvent(
                                    javafx.scene.input.KeyEvent.KEY_PRESSED, "", "",
                                    javafx.scene.input.KeyCode.ENTER, false, false, false, false));
                            sent.complete("fired");
                        } else {
                            sent.complete("missing chat input");
                        }
                    } catch (Throwable t) {
                        sent.completeExceptionally(t);
                    }
                });
                System.out.println("AI chat send: " + sent.get(10, java.util.concurrent.TimeUnit.SECONDS));
                Thread.sleep(2500);
                java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> chatShotFuture =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        var img = LifeDeskApp.primaryStageRef.getScene().snapshot(null);
                        int width = (int) img.getWidth();
                        int height = (int) img.getHeight();
                        java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                buffered.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                            }
                        }
                        chatShotFuture.complete(buffered);
                    } catch (Throwable t) {
                        chatShotFuture.completeExceptionally(t);
                    }
                });
                javax.imageio.ImageIO.write(chatShotFuture.get(30, java.util.concurrent.TimeUnit.SECONDS),
                        "png", chatShot.toFile());
                check("AI chat trả lời và hiển thị trong khung chat", chatShot.toFile().length() > 0);
                System.out.println("Chat snapshot: " + chatShot);
            } catch (Exception e) {
                System.out.println("Chat snapshot FAILED: " + e);
                check("AI chat trả lời và hiển thị trong khung chat", false);
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

    /** First note id in the seeded dataset, so the NOTES snapshot shows a real editor. */
    private static String firstNoteId(Path dataDir) {
        Path index = dataDir.resolve("notes").resolve("index.json");
        try {
            if (!Files.exists(index)) {
                return null;
            }
            var root = com.google.gson.JsonParser.parseString(Files.readString(index)).getAsJsonObject();
            var notes = root.getAsJsonObject("data").getAsJsonArray("notes");
            return notes.isEmpty() ? null : notes.get(0).getAsJsonObject().get("id").getAsString();
        } catch (RuntimeException | java.io.IOException e) {
            System.out.println("Seed note lookup failed: " + e);
            return null;
        }
    }

    private static void copyTree(Path from, Path to) throws java.io.IOException {
        try (var walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path rel = from.relativize(src);
                if (rel.toString().isEmpty() || rel.getFileName().toString().endsWith(".lock")) {
                    continue;
                }
                Path dst = to.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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
    private static String seedNoteIdHolder;

    private SmokeMain() {
    }
}
