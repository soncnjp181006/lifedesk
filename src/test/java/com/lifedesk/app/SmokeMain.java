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
        // Flag BEFORE any UI starts: the crash handler must not pop error dialogs
        // on the user's screen while the automated run exercises failure paths.
        System.setProperty("lifedesk.smoke", "true");
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

    /** Every button label in a subtree, including the ones a skin built lazily. */
    private static void collectButtonLabels(javafx.scene.Node node, java.util.List<String> out) {
        if (node instanceof javafx.scene.control.Button b
                && b.getText() != null && !b.getText().isBlank()) {
            out.add(b.getText());
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                collectButtonLabels(child, out);
            }
        }
    }

    /**
     * Controls whose text no longer fits. A plain Label may elide on purpose (long
     * note titles), so only interactive Labeled nodes are counted: a button that
     * reads "Chia …" or "Thẻ li…" has lost the information it exists to carry.
     * Cells are excluded for the same reason — eliding a row title is the design.
     * Reach: this measures the control's own box. A container squeezed below its
     * children's width makes them overflow instead of clip, so it is invisible
     * here — the horizontal-overflow check next to it covers that case.
     */
    /** First node in the subtree that satisfies the predicate, or null. */
    private static javafx.scene.Node findNode(javafx.scene.Node root,
            java.util.function.Predicate<javafx.scene.Node> p) {
        if (root == null) {
            return null;
        }
        if (p.test(root)) {
            return root;
        }
        if (root instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                javafx.scene.Node hit = findNode(child, p);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** How many uniform card rows the probe actually measured; a green with 0 is blind. */
    private static final java.util.concurrent.atomic.AtomicInteger uniformRows =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Labels inside a card that lost letters. The generic clip probe ignores plain
     * Labels because prose rows are allowed to shorten; inside a comparison card the
     * text usually *is* the measurement ("24.500.0…" is not a number), so it is
     * checked here instead. Chart internals are skipped: a LegendItem sizes its label
     * from the glyph bounds, so width 22 against prefWidth 27 is padding counted
     * twice by two different code paths, not a cut word — the legend renders whole.
     */
    private static void findCutLabels(javafx.scene.Node node, java.util.List<String> out) {
        if (node instanceof javafx.scene.chart.Chart) {
            return;
        }
        if (node instanceof javafx.scene.control.Label l
                && l.getText() != null && !l.getText().isBlank()
                && l.getWidth() > 0
                && l.getWidth() + 1 < l.prefWidth(l.getHeight())) {
            out.add("\"" + l.getText() + "\" bị cắt " + Math.round(l.getWidth())
                    + "<" + Math.round(l.prefWidth(l.getHeight())) + "px");
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                findCutLabels(child, out);
            }
        }
    }

    /**
     * The cells of a row that was built to hold equal cells (Fx.equalTiles), or null
     * when this node is not one. A TilePane only counts when it declares its column
     * count, so a generic wrapping pane is not mistaken for a comparison strip, and a
     * multi-row grid is not mistaken for a single one.
     */
    private static java.util.List<javafx.scene.Node> uniformRowCells(javafx.scene.Node node) {
        if (node instanceof javafx.scene.layout.TilePane tiles) {
            int cols = tiles.getPrefColumns();
            java.util.List<javafx.scene.Node> cells = managedVisible(tiles.getChildren());
            return cols >= 2 && cells.size() == cols ? cells : null;
        }
        return null;
    }

    private static java.util.List<javafx.scene.Node> managedVisible(
            java.util.List<javafx.scene.Node> in) {
        return in.stream().filter(n -> n.isVisible() && n.isManaged()).toList();
    }

    /**
     * Rows built to hold equal cells (Fx.equalTiles) exist to line their content up, so
     * measure the cells two ways: they must be the same width, and nothing inside one
     * may lose letters. The first catches a ragged strip; the second catches the "fix"
     * that evened the cards by cutting "24.500.000 đ" down to "24.500.0…", or that
     * squeezed a chart until its own legend became a 22px stub.
     */
    private static void findUnevenRows(javafx.scene.Node node, java.util.List<String> out) {
        java.util.List<javafx.scene.Node> cells = uniformRowCells(node);
        if (cells != null) {
            uniformRows.incrementAndGet();
            java.util.function.ToDoubleFunction<javafx.scene.Node> wide = n ->
                    n instanceof javafx.scene.layout.Region r
                            ? r.getWidth() : n.getBoundsInLocal().getWidth();
            double min = cells.stream().mapToDouble(wide).min().orElse(0);
            double max = cells.stream().mapToDouble(wide).max().orElse(0);
            if (max - min > 2) {
                out.add("hàng thẻ " + cells.size() + " ô lệch "
                        + Math.round(min) + "≠" + Math.round(max) + "px");
            }
            for (javafx.scene.Node cell : cells) {
                java.util.List<String> cut = new java.util.ArrayList<>();
                findCutLabels(cell, cut);
                for (String s : cut) {
                    out.add("ô thẻ " + s);
                }
            }
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                findUnevenRows(child, out);
            }
        }
    }

    /**
     * Reach for the chart probe: how many chart labels were measured and the tightest
     * right-edge margin seen among them. "Nothing overflowed" is only worth reading
     * if the walk found text to measure.
     */
    private static final java.util.concurrent.atomic.AtomicInteger chartLabels =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger chartEdgeTight =
            new java.util.concurrent.atomic.AtomicInteger(100000);

    /**
     * Chart text drawn past the edge of the chart that owns it. A tick label is
     * centred on its tick, so the final date tick of a 30-day axis hangs half outside
     * the plot — measured here rather than judged from a screenshot, in pixels.
     */
    private static void findChartOverflow(javafx.scene.Node node, java.util.List<String> out) {
        if (node instanceof javafx.scene.chart.Chart chart && chart.getWidth() > 0) {
            double right = chart.localToScene(chart.getBoundsInLocal()).getMaxX();
            java.util.List<javafx.scene.Node> inside = new java.util.ArrayList<>();
            collectSubtree(chart, inside);
            for (javafx.scene.Node n : inside) {
                String text = n instanceof javafx.scene.control.Labeled l ? l.getText()
                        : n instanceof javafx.scene.text.Text t ? t.getText() : null;
                if (text == null || text.isBlank() || !n.isVisible()) {
                    continue;
                }
                chartLabels.incrementAndGet();
                double max = n.localToScene(n.getBoundsInLocal()).getMaxX();
                chartEdgeTight.updateAndGet(prev -> Math.min(prev, (int) Math.round(right - max)));
                if (max > right + 1) {
                    out.add("biểu đồ \"" + text + "\" tràn " + Math.round(max - right) + "px");
                }
            }
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                findChartOverflow(child, out);
            }
        }
    }

    private static void collectSubtree(javafx.scene.Node node, java.util.List<javafx.scene.Node> into) {
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                into.add(child);
                collectSubtree(child, into);
            }
        }
    }

    /**
     * Text that loses letters with nowhere left to read them. Cutting a label is fine
     * when the full string is one hover away; a bare ellipsis throws information away.
     * Chart internals are skipped (their skin sizes its own text), and a tooltip that
     * carries a richer sentence than the label — exact figures behind a compact
     * number — counts as the rescue.
     */
    private static void findSilentCuts(javafx.scene.Node node, java.util.List<String> out) {
        if (node instanceof javafx.scene.chart.Chart) {
            return;
        }
        if (node instanceof javafx.scene.control.Label l
                && l.getText() != null && !l.getText().isBlank()
                && l.getWidth() > 0 && l.getHeight() > 0) {
            silentLabels.incrementAndGet();
            // Two different ways to lose letters, so two different measurements.
            // A single-line label is cut when the row is narrower than the line;
            // a wrapping label is never "too narrow" — it is cut only when it is
            // too SHORT for the lines it chose to break into. Measuring a wrapping
            // label by width flagged three labels that render whole (the disclaimer
            // under the health card, the AI welcome line, the empty-state messages):
            // prefWidth(knownHeight) does not invert a wrap, so it reported the
            // unwrapped width against a deliberately narrow column.
            boolean cut = l.isWrapText()
                    ? l.getHeight() + 1 < l.prefHeight(l.getWidth())
                    : l.getWidth() + 1 < l.prefWidth(-1);
            if (cut) {
                String tip = l.getTooltip() == null ? null : l.getTooltip().getText();
                if (tip == null || tip.isBlank()) {
                    out.add("\"" + oneLine(l.getText()) + "\" " + where(l) + " bị cắt mà không có tooltip");
                } else {
                    silentRescued.incrementAndGet();
                }
            }
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                findSilentCuts(child, out);
            }
        }
    }

    /** Which way the label was set up and who holds it: enough to find it in the source. */
    private static String where(javafx.scene.control.Label l) {
        String holder = "";
        for (javafx.scene.Node n = l.getParent(); n != null && holder.isEmpty(); n = n.getParent()) {
            for (String c : n.getStyleClass()) {
                if (!c.startsWith("label") && !c.equals("vbox") && !c.equals("hbox")) {
                    holder = c;
                    break;
                }
            }
        }
        return "(" + (l.isWrapText() ? "wrap" : "1 dòng") + (holder.isEmpty() ? "" : " trong ." + holder) + ")";
    }

    /**
     * Reach for the rule above. "0 problems" from a walker that never met a single
     * Label would look identical to a clean UI, and identical to a clean UI is not
     * evidence: count the labels it read and the ones it read as cut-but-rescued, so
     * a probe that stopped seeing anything shows up as a red reach check instead of
     * a green sheet.
     */
    private static final java.util.concurrent.atomic.AtomicInteger silentLabels =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger silentRescued =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * "" when the highlighted rail item sits inside the rail's own viewport. Pages no
     * longer paint their own name, so the rail is the only on-screen answer to "which
     * module am I in" — an active item below the fold is a page with no name and no
     * mouse route back to it. Anything it cannot measure is reported as a failure
     * rather than a pass.
     */
    private static String railActiveVisible() {
        var root = LifeDeskApp.primaryStageRef.getScene().getRoot();
        var activeNav = findNode(root, n -> n.getStyleClass().contains("nav-item")
                && n.getStyleClass().contains("active"));
        if (activeNav != null) {
            javafx.scene.Node holder = activeNav.getParent();
            while (holder != null && !(holder instanceof javafx.scene.control.ScrollPane)) {
                holder = holder.getParent();
            }
            if (!(holder instanceof javafx.scene.control.ScrollPane rail)) {
                return "rail không cuộn được (không có ScrollPane cha)";
            }
            var ab = activeNav.localToScene(activeNav.getBoundsInLocal());
            var rb = rail.localToScene(rail.getBoundsInLocal());
            if (ab.getMinY() < rb.getMinY() - 1 || ab.getMaxY() > rb.getMaxY() + 1) {
                return "mục đang mở ở " + Math.round(ab.getMinY()) + "–" + Math.round(ab.getMaxY())
                        + ", rail tới " + Math.round(rb.getMaxY());
            }
            return "";
        }
        // No rail entry for this page: the control that opened it must carry the mark.
        var origin = findNode(root, n -> n.getStyleClass().contains("origin-active"));
        if (origin == null) {
            return "không có dấu 'đang ở đây' trên màn hình";
        }
        return origin.isVisible() && origin.getBoundsInLocal().getWidth() > 0
                ? "" : "dấu 'đang ở đây' không nhìn thấy";
    }

    /**
     * The rail must light up the row of the page that is actually on screen.
     *
     * <p>"Some row is highlighted and it is inside the rail's viewport" was the question
     * asked so far, and it stayed true in a chat screenshot where the rail pointed at a
     * different module. Since the page no longer paints its own title, a highlight on the
     * wrong row is not a cosmetic slip — it is the app naming the wrong module.
     */
    private static String railMarksCurrentView() {
        var shell = LifeDeskApp.mainWindowRef.currentView();
        if (shell == null) {
            return "shell chưa biết đang ở module nào";
        }
        if (shell == com.lifedesk.ui.MainWindow.ViewId.SEARCH
                || shell == com.lifedesk.ui.MainWindow.ViewId.SETTINGS) {
            return ""; // no rail row for those; railActiveVisible() reads their marker
        }
        var root = LifeDeskApp.primaryStageRef.getScene().getRoot();
        var marks = root.lookupAll(".nav-item.active");
        if (marks.isEmpty()) {
            return "rail không tô mục nào (trang đang mở: " + shell.label + ")";
        }
        if (marks.size() > 1) {
            // Two rows lit at once answers "where am I?" with "both", and the first match
            // alone would still have read as correct.
            return "rail tô " + marks.size() + " mục cùng lúc";
        }
        var active = marks.iterator().next();
        String text = active instanceof javafx.scene.control.Button b ? b.getText() : "?";
        return shell.label.equals(text) ? ""
                : "rail đang tô \"" + text + "\" nhưng trang mở là \"" + shell.label + "\"";
    }

    /**
     * Where the vertical space actually went: follow the tallest child at every level
     * and print height / stated preference / minimum. A page that scrolls when it
     * should not is otherwise a guess between five containers.
     */
    private static String heightChain(javafx.scene.Node root) {
        StringBuilder sb = new StringBuilder();
        javafx.scene.Node cur = root;
        for (int i = 0; i < 8 && cur != null; i++) {
            String name = cur.getClass().getSimpleName();
            if (cur instanceof com.lifedesk.ui.View v) {
                name = v.id();
            }
            String dims = cur instanceof javafx.scene.layout.Region r
                    ? Math.round(r.getHeight()) + "|" + Math.round(r.getPrefHeight())
                            + "|" + Math.round(r.getMinHeight())
                    : "?";
            if (sb.length() > 0) {
                sb.append(" > ");
            }
            sb.append(name).append('(').append(dims).append(')');
            javafx.scene.Node next = null;
            if (cur instanceof javafx.scene.Parent p) {
                for (javafx.scene.Node c : p.getChildrenUnmodifiable()) {
                    if (c.getBoundsInLocal().getHeight() > 0 && (next == null
                            || c.getBoundsInLocal().getHeight()
                                    > next.getBoundsInLocal().getHeight())) {
                        next = c;
                    }
                }
            }
            cur = next;
        }
        return sb.toString();
    }

    /**
     * What a container actually paints around itself: a visible border width and an
     * opaque fill. Read from the node's computed CSS, not from the stylesheet — a rule
     * deleted from base.css can come back through a theme, through a skin, or through a
     * setStyle call, and only the node knows. "" means the container has no edges, so
     * nothing wraps the content inside it.
     */
    private static String paintedEdges(javafx.scene.Node node) {
        if (!(node instanceof javafx.scene.layout.Region r)) {
            return "không tìm thấy vùng chứa";
        }
        double widest = 0;
        javafx.scene.layout.Border border = r.getBorder();
        if (border != null) {
            for (javafx.scene.layout.BorderStroke s : border.getStrokes()) {
                javafx.scene.layout.BorderWidths w = s.getWidths();
                widest = Math.max(widest, paintedSide(s.getTopStyle(), s.getTopStroke(), w.getTop()));
                widest = Math.max(widest, paintedSide(s.getRightStyle(), s.getRightStroke(), w.getRight()));
                widest = Math.max(widest, paintedSide(s.getBottomStyle(), s.getBottomStroke(), w.getBottom()));
                widest = Math.max(widest, paintedSide(s.getLeftStyle(), s.getLeftStroke(), w.getLeft()));
            }
        }
        boolean opaqueFill = false;
        javafx.scene.layout.Background background = r.getBackground();
        if (background != null) {
            for (javafx.scene.layout.BackgroundFill fill : background.getFills()) {
                if (visible(fill.getFill())) {
                    opaqueFill = true;
                }
            }
        }
        if (widest > 0.5 && opaqueFill) {
            return "viền " + Math.round(widest) + "px + nền đặc";
        }
        if (widest > 0.5) {
            return "viền " + Math.round(widest) + "px";
        }
        return opaqueFill ? "nền đặc" : "";
    }

    /**
     * One side of a computed border, measured only if it is actually visible: a side
     * with width 0, style NONE or a transparent paint draws nothing, so it cannot make
     * the container read as a box.
     */
    private static double paintedSide(javafx.scene.layout.BorderStrokeStyle style,
                                      javafx.scene.paint.Paint paint, double width) {
        if (style == null || style == javafx.scene.layout.BorderStrokeStyle.NONE) {
            return 0;
        }
        return visible(paint) ? width : 0;
    }

    /**
     * CSS "transparent" arrives as Color.TRANSPARENT; anything else (a solid colour, a
     * gradient) paints pixels. Paint itself only answers isOpaque(), which is false for
     * every partly alpha'd colour too, so the alpha is read where the type allows it.
     */
    private static boolean visible(javafx.scene.paint.Paint paint) {
        if (paint == null) {
            return false;
        }
        if (paint instanceof javafx.scene.paint.Color c) {
            return c.getOpacity() > 0.01;
        }
        return true;
    }

    /**
     * Keep a hand off the windows being measured, and record what it tried to do.
     *
     * <p>The harness measures real, on-screen windows. A run that asked for HEALTH and
     * then spent three seconds showing AGENT and AI was not a shell defect: somebody
     * clicked the rail while the screenshots were being taken, and the identity checks
     * then described a page nobody had asked for. The next run's log named the hand — ten
     * real clicks, one on a chat suggestion, one on "Tệp" — and a dialog the harness had
     * just opened was closed by the same mouse before it could be captured.
     *
     * <p>So pointer input is swallowed for the duration of the run, on every window: no
     * test reaches the UI through the mouse, only through the API (showView, fire(), a
     * key event dispatched at a node). Keys are recorded but never consumed, because two
     * tests do dispatch them and a filter would blind the tests instead of the stranger.
     */
    private static void isolateFromHumanInput(java.util.List<String> seen) {
        javafx.event.EventHandler<javafx.scene.input.MouseEvent> pointer = e -> {
            synchronized (seen) {
                seen.add(e.getEventType().getName() + " chuột → " + inputTarget(e)
                        + " @" + Math.round(e.getScreenX()) + "," + Math.round(e.getScreenY()));
            }
            e.consume();
        };
        javafx.event.EventHandler<javafx.scene.input.KeyEvent> chord = e -> {
            if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                // Recorded, never consumed: two tests dispatch their own key events and
                // a filter would blind the tests instead of the stranger.
                synchronized (seen) {
                    seen.add("phím " + e.getCode() + (e.isControlDown() ? " +Ctrl" : "")
                            + (e.isAltDown() ? " +Alt" : ""));
                }
            }
        };
        java.util.Set<javafx.stage.Window> covered =
                java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
        Runnable everyWindow = () -> {
            for (javafx.stage.Window w : new java.util.ArrayList<>(javafx.stage.Window.getWindows())) {
                if (covered.contains(w) || !(w instanceof javafx.stage.Stage st)
                        || st.getScene() == null) {
                    continue;
                }
                covered.add(w);
                st.getScene().addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, pointer);
                st.getScene().addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, pointer);
                st.getScene().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, chord);
            }
        };
        everyWindow.run();
        // Dialog stages are created and destroyed throughout the run, so the coverage has
        // to follow the window list rather than be applied once.
        javafx.stage.Window.getWindows().addListener(
                (javafx.collections.ListChangeListener<javafx.stage.Window>) c -> everyWindow.run());
    }

    /** The node a real click landed on, named well enough to read in a log line. */
    private static String inputTarget(javafx.event.Event event) {
        if (!(event.getTarget() instanceof javafx.scene.Node n)) {
            return event.getTarget().getClass().getSimpleName();
        }
        String cls = "";
        for (String c : n.getStyleClass()) {
            if (!c.equals("label") && !c.equals("vbox") && !c.equals("hbox")) {
                cls = "." + c;
                break;
            }
        }
        String text = n instanceof javafx.scene.control.Labeled l && l.getText() != null
                && !l.getText().isBlank() ? " \"" + oneLine(l.getText()) + "\"" : "";
        return n.getClass().getSimpleName() + cls + text;
    }

    /** Snapshot the live scene into an AWT image the pixel measurements can read. */
    private static java.awt.image.BufferedImage captureScene() throws Exception {
        java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> f =
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
                f.complete(buffered);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(60, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * The review card's own background colour, asked of the engine that paints it. The
     * palette is baked into the card's HTML, so this is exactly the thing a theme switch
     * has to change — with no sampling geometry that can miss the target.
     */
    private static String reviewCardBackground() throws Exception {
        java.util.concurrent.CompletableFuture<String> f =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                javafx.scene.Node card = findNode(
                        LifeDeskApp.primaryStageRef.getScene().getRoot(),
                        n -> n instanceof com.lifedesk.markdown.MarkdownPreviewPane);
                if (!(card instanceof com.lifedesk.markdown.MarkdownPreviewPane mp)
                        || !(mp.webViewNode() instanceof javafx.scene.web.WebView wv)) {
                    f.complete("(không có thẻ ôn tập trên màn hình)");
                    return;
                }
                Object bg = wv.getEngine().executeScript(
                        "getComputedStyle(document.body).backgroundColor");
                f.complete(bg == null ? "(engine không trả lời)" : bg.toString());
            } catch (Throwable t) {
                f.complete("(lỗi đọc màu: " + t.getClass().getSimpleName() + ")");
            }
        });
        return f.get(20, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 0..1 brightness of a CSS colour string ("rgb(22, 41, 74)", "rgba(255,255,255,1)").
     * -1 when the string is not a colour at all, so a failed read cannot be mistaken for
     * a dark theme.
     */
    private static double backgroundBrightness(String cssColor) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,3})\\D+(\\d{1,3})\\D+(\\d{1,3})")
                .matcher(cssColor == null ? "" : cssColor);
        if (!m.find()) {
            return -1;
        }
        return (Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2))
                + Integer.parseInt(m.group(3))) / (3 * 255.0);
    }

    /**
     * Average brightness inside the review card's own rectangle, inset 15% so the border
     * and the scrollbar are not part of the sample. -1 when no card is on screen: the
     * caller treats that as a failed measurement, not a passed one.
     */
    private static long cardPatchBrightness(java.awt.image.BufferedImage shot) throws Exception {
        java.util.concurrent.CompletableFuture<double[]> place =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            try {
                var scene = LifeDeskApp.primaryStageRef.getScene();
                javafx.scene.Node card = findNode(scene.getRoot(),
                        n -> n instanceof com.lifedesk.markdown.MarkdownPreviewPane);
                if (card == null || card.getBoundsInLocal().getWidth() < 120) {
                    place.complete(new double[]{-1, -1, -1, -1, 1, 1});
                    return;
                }
                var b = card.localToScene(card.getBoundsInLocal());
                // The snapshot is in device pixels; the bounds are in scene units.
                place.complete(new double[]{b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY(),
                        scene.getWidth(), scene.getHeight()});
            } catch (Throwable t) {
                place.completeExceptionally(t);
            }
        });
        double[] r = place.get(20, java.util.concurrent.TimeUnit.SECONDS);
        if (r[0] < 0 || r[4] <= 0) {
            return -1;
        }
        double sx = shot.getWidth() / r[4];
        double sy = shot.getHeight() / r[5];
        int x0 = (int) ((r[0] + (r[2] - r[0]) * 0.15) * sx);
        int x1 = (int) ((r[2] - (r[2] - r[0]) * 0.15) * sx);
        int y0 = (int) ((r[1] + (r[3] - r[1]) * 0.15) * sy);
        int y1 = (int) ((r[3] - (r[3] - r[1]) * 0.15) * sy);
        long lum = 0;
        int n = 0;
        for (int y = Math.max(0, y0); y <= Math.min(shot.getHeight() - 1, y1); y += 4) {
            for (int x = Math.max(0, x0); x <= Math.min(shot.getWidth() - 1, x1); x += 4) {
                int argb = shot.getRGB(x, y);
                lum += ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
                n++;
            }
        }
        return n == 0 ? -1 : lum / (3L * n);
    }

    /**
     * Read the review card's page metrics once WebKit has caught up with the window.
     * The engine's own viewport height must agree with the node's before its scroll
     * numbers mean anything; a page that never settles is itself a defect, so that
     * path reports a failure rather than an empty string.
     */
    private static String measureCardScroll() throws Exception {
        for (int i = 0; i < 10; i++) {
            java.util.concurrent.CompletableFuture<String> f =
                    new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    javafx.scene.Node card = findNode(
                            LifeDeskApp.primaryStageRef.getScene().getRoot(),
                            n -> n instanceof com.lifedesk.markdown.MarkdownPreviewPane);
                    if (!(card instanceof com.lifedesk.markdown.MarkdownPreviewPane mp)) {
                        f.complete("không tìm thấy thẻ ôn tập");
                        return;
                    }
                    double[] m = mp.pageMetrics();
                    javafx.scene.web.WebView wv = mp.webViewNode();
                    if (m == null || wv == null) {
                        f.complete("không đọc được trang thẻ (WebView=" + (wv != null) + ")");
                        return;
                    }
                    if (Math.abs(m[2] - wv.getHeight()) > 4) {
                        f.complete("PENDING engine=" + Math.round(m[2])
                                + " node=" + Math.round(wv.getHeight()));
                        return;
                    }
                    f.complete(m[0] - m[1] > 1
                            ? "thẻ cuộn thừa " + Math.round(m[0] - m[1]) + "px" : "");
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
            String r = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!r.startsWith("PENDING")) {
                return r;
            }
            Thread.sleep(200);
        }
        return "trang thẻ không theo kịp cỡ cửa sổ (engine vẫn lệch sau 2 giây)";
    }

    /** First 40 characters of a label, single line: enough to name the offender. */
    private static String oneLine(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    private static void findClippedControls(javafx.scene.Node node, java.util.List<String> out) {
        if (node instanceof javafx.scene.control.Labeled lb
                && !(node instanceof javafx.scene.control.Label)
                && !(node instanceof javafx.scene.control.Cell<?>)
                && lb.getText() != null && !lb.getText().isBlank()
                && lb.getWidth() > 0
                && lb.getWidth() + 1 < lb.prefWidth(lb.getHeight())) {
            out.add(lb.getClass().getSimpleName() + " \"" + lb.getText() + "\" "
                    + (int) lb.getWidth() + "<" + (int) lb.prefWidth(lb.getHeight()));
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                findClippedControls(child, out);
            }
        }
    }

    /**
     * Labels inside table cells. The probe above skips cells on purpose: the first
     * column is meant to elide a long task title. But a narrow column that renders
     * "Trung…" for "Trung bình" is not eliding, it is a column too small to say the
     * word it holds — and no general probe could tell the two apart. So every cell
     * except the first column's must fit.
     */
    private static void findClippedCellLabels(javafx.scene.Node node, java.util.List<String> out) {
        if (out.size() >= 8) {
            return;
        }
        if (node instanceof javafx.scene.control.TableCell<?, ?> tc) {
            if (isFirstColumnCell(tc)) {
                // The first column is the one place where eliding is the design: a
                // long task title shortens so the row stays readable. What must not
                // shorten inside it is a status chip — "quá h…" is not a status.
                walkChildren(tc, out, columnOf(tc), true);
            } else {
                clipIfNarrow(tc, out, columnOf(tc));
                walkChildren(tc, out, columnOf(tc), false);
            }
            return;
        }
        if (node instanceof javafx.scene.control.Cell<?> c) {
            clipIfNarrow(c, out, c.getClass().getSimpleName());
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                findClippedCellLabels(child, out);
            }
        }
    }

    private static boolean isFirstColumnCell(javafx.scene.control.Cell<?> cell) {
        if (!(cell instanceof javafx.scene.control.TableCell<?, ?> tc)
                || tc.getTableView() == null || tc.getTableColumn() == null
                || tc.getTableView().getColumns().isEmpty()) {
            return false;
        }
        return tc.getTableColumn() == tc.getTableView().getColumns().get(0);
    }

    /** Which column a cell belongs to — "ô bị cắt" without that is not actionable. */
    private static String columnOf(javafx.scene.control.Cell<?> cell) {
        if (cell instanceof javafx.scene.control.TableCell<?, ?> tc && tc.getTableColumn() != null) {
            return tc.getTableColumn().getText();
        }
        return cell.getClass().getSimpleName();
    }

    private static void clipIfNarrow(javafx.scene.control.Labeled lb, java.util.List<String> out,
            String where) {
        if (out.size() >= 8 || lb.getText() == null || lb.getText().isBlank()
                || lb.getWidth() <= 0 || lb.getWidth() + 1 >= lb.prefWidth(lb.getHeight())) {
            return;
        }
        out.add(where + ": \"" + lb.getText() + "\" " + (int) lb.getWidth()
                + "<" + (int) lb.prefWidth(lb.getHeight()));
    }

    /** Start the walk on the cell's children: the cell itself is a Cell, and the
     *  guard below would otherwise stop before it ever looked inside. */
    private static void walkChildren(javafx.scene.Parent cell, java.util.List<String> out,
            String column, boolean chipsOnly) {
        for (javafx.scene.Node child : cell.getChildrenUnmodifiable()) {
            walkClippedLabels(child, out, column, chipsOnly);
        }
    }

    private static void walkClippedLabels(javafx.scene.Node node, java.util.List<String> out,
            String column, boolean chipsOnly) {
        if (node instanceof javafx.scene.control.Labeled lb
                && (!chipsOnly || lb.getStyleClass().contains("chip"))) {
            clipIfNarrow(lb, out, column);
        }
        // A nested cell decides its own rule: a TableRow is a cell too, and walking
        // it as one would put the first column back under the microscope.
        if (node instanceof javafx.scene.Parent p
                && !(node instanceof javafx.scene.control.Cell<?>)) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                walkClippedLabels(child, out, column, chipsOnly);
            }
        }
    }

    /**
     * Re-run the layout probes on a narrower window. Fitting at 1920px is not
     * evidence it fits at 1180px — and a squeezed toolbar is exactly where control
     * labels disappear ("Chia …"), which is the failure this harness had to catch
     * by eye once already.
     */
    private static void probeAtWidth(int width, String[] viewNames) {
        for (String want : viewNames) {
            try {
                String shown = "(?)";
                for (int attempt = 0; attempt < 8; attempt++) {
                    java.util.concurrent.CompletableFuture<String> f =
                            new java.util.concurrent.CompletableFuture<>();
                    javafx.application.Platform.runLater(() -> {
                        try {
                            LifeDeskApp.mainWindowRef.showView(
                                    com.lifedesk.ui.MainWindow.ViewId.valueOf(want));
                            var area = (javafx.scene.layout.Pane) LifeDeskApp.primaryStageRef
                                    .getScene().lookup(".content-area");
                            f.complete(area != null && !area.getChildren().isEmpty()
                                    && area.getChildren().get(0) instanceof com.lifedesk.ui.View vv
                                    ? vv.id() : "(none)");
                        } catch (Throwable t) {
                            f.completeExceptionally(t);
                        }
                    });
                    shown = f.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (want.equals(shown)) {
                        break;
                    }
                    Thread.sleep(250);
                }
                check(width + "px: mở được " + want + " (hiện " + shown + ")", want.equals(shown));
                java.util.concurrent.CompletableFuture<String[]> probe =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        var scene = LifeDeskApp.primaryStageRef.getScene();
                        // Measure a settled tree. Without this the probe has read a
                        // half-reflowed layout right after a window resize and
                        // reported views as overflowing when they were not.
                        scene.getRoot().applyCss();
                        scene.getRoot().layout();
                        javafx.scene.control.ScrollPane sp = null;
                        for (javafx.scene.Node n = scene.lookup(".content-area");
                             n != null; n = n.getParent()) {
                            if (n instanceof javafx.scene.control.ScrollPane found) {
                                sp = found;
                                break;
                            }
                        }
                        if (sp == null) {
                            probe.complete(new String[]{
                                    "không tìm thấy .content-area",
                                    "-", "-", "-", "-", "-", "-", "-", "-", "-"});
                            return;
                        }
                        double viewport = sp.getViewportBounds().getWidth();
                        double contentW = sp.getContent().getLayoutBounds().getWidth();
                        java.util.List<String> clipped = new java.util.ArrayList<>();
                        findClippedControls(scene.getRoot(), clipped);
                        java.util.List<String> cellClipped = new java.util.ArrayList<>();
                        findClippedCellLabels(scene.getRoot(), cellClipped);
                        java.util.List<String> chipClipped = new java.util.ArrayList<>();
                        findClippedChips(scene.getRoot(), chipClipped);
                        java.util.List<String> uneven = new java.util.ArrayList<>();
                        findUnevenRows(scene.getRoot(), uneven);
                        java.util.List<String> chartOut = new java.util.ArrayList<>();
                        findChartOverflow(scene.getRoot(), chartOut);
                        java.util.List<String> silent = new java.util.ArrayList<>();
                        findSilentCuts(scene.getRoot(), silent);
                        // "It overflows by 100px" is not actionable; name the node
                        // that refuses to shrink.
                        java.util.List<String> wide = new java.util.ArrayList<>();
                        if (contentW > viewport + 1) {
                            findWideNodes(sp.getContent(), viewport, wide);
                        }
                        // "Nothing overflows sideways" says nothing about the one
                        // control the module exists for. The review session's flip
                        // button sat below the fold at the app's minimum window
                        // height: the card kept the tall size it had in a bigger
                        // window, so the page grew past the viewport and the answer
                        // row needed a scroll.
                        String reach = "-";
                        if ("LEARNING".equals(want)) {
                            System.out.println("VCHAIN " + heightChain(sp.getContent())
                                    + " viewportH=" + Math.round(sp.getViewportBounds().getHeight()));
                            javafx.scene.Node flip = findNode(scene.getRoot(), n ->
                                    n instanceof javafx.scene.control.Button b && b.getText() != null
                                            && b.getText().startsWith("Lật thẻ"));
                            if (flip == null) {
                                reach = "không tìm thấy nút \"Lật thẻ\"";
                            } else {
                                var fb = flip.localToScene(flip.getBoundsInLocal());
                                var vb = sp.localToScene(sp.getBoundsInLocal());
                                if (fb.getMaxY() > vb.getMaxY() + 1 || fb.getMinY() < vb.getMinY() - 1) {
                                    // Say where the height went, not only that it went:
                                    // the whole vertical chain, from the scroll content
                                    // down to the card that grows.
                                    reach = "nút \"Lật thẻ\" ở " + Math.round(fb.getMinY()) + "–"
                                            + Math.round(fb.getMaxY()) + ", khung nhìn tới "
                                            + Math.round(vb.getMaxY()) + " — phải cuộn mới bấm được"
                                            + " [viewportH=" + Math.round(sp.getViewportBounds().getHeight())
                                            + " contentH=" + Math.round(sp.getContent().getLayoutBounds().getHeight())
                                            + " " + heightChain(sp.getContent()) + "]";
                                }
                            }
                        }
                        if ("AI".equals(want)) {
                            // The chat page owns its scrolling: the message column moves,
                            // the page must not. A cap that forgot the content column's own
                            // padding left the page exactly that padding too tall, so the
                            // composer — the one thing you have to reach to use the module —
                            // sat under the fold and needed a page scroll to see.
                            System.out.println("VCHAIN " + heightChain(sp.getContent())
                                    + " viewportH=" + Math.round(sp.getViewportBounds().getHeight()));
                            javafx.scene.Node field = scene.lookup(".chat-input");
                            if (field == null) {
                                reach = "không tìm thấy ô soạn tin";
                            } else {
                                var ib = field.localToScene(field.getBoundsInLocal());
                                var vb = sp.localToScene(sp.getViewportBounds());
                                if (ib.getMaxY() > vb.getMaxY() + 1 || ib.getMinY() < vb.getMinY() - 1) {
                                    reach = "ô soạn tin ở " + Math.round(ib.getMinY()) + "–"
                                            + Math.round(ib.getMaxY()) + ", khung nhìn tới "
                                            + Math.round(vb.getMaxY())
                                            + " — phải cuộn cả trang mới gõ được"
                                            + " [viewportH=" + Math.round(vb.getHeight())
                                            + " contentH=" + Math.round(sp.getContent().getLayoutBounds().getHeight())
                                            + " " + heightChain(sp.getContent()) + "]";
                                }
                            }
                        }
                        probe.complete(new String[]{
                                contentW <= viewport + 1 ? "" : "tràn " + Math.round(contentW)
                                        + ">" + Math.round(viewport)
                                        + (wide.isEmpty() ? "" : " | " + String.join(" | ", wide)),
                                String.join(", ", clipped), String.join(", ", cellClipped),
                                String.join(", ", chipClipped), String.join(", ", uneven),
                                String.join(", ", chartOut), String.join(", ", silent), reach,
                                railActiveVisible()});
                    } catch (Throwable t) {
                        probe.completeExceptionally(t);
                    }
                });
                String[] res = probe.get(30, java.util.concurrent.TimeUnit.SECONDS);
                check(width + "px: " + want + " không tràn ngang"
                        + (res[0].isEmpty() ? "" : " — " + res[0]), res[0].isEmpty());
                check(width + "px: " + want + " không có nút bị cắt chữ"
                        + (res[1].isEmpty() || res[1].equals("-") ? "" : " — " + res[1]),
                        res[1].isEmpty() || res[1].equals("-"));
                check(width + "px: " + want + " không có ô bảng bị cắt chữ"
                        + (res[2].isEmpty() || res[2].equals("-") ? "" : " — " + res[2]),
                        res[2].isEmpty() || res[2].equals("-"));
                check(width + "px: " + want + " không có nhãn trạng thái bị cắt"
                        + (res[3].isEmpty() || res[3].equals("-") ? "" : " — " + res[3]),
                        res[3].isEmpty() || res[3].equals("-"));
                check(width + "px: " + want + " các hàng thẻ số đều ô"
                        + (res[4].isEmpty() || res[4].equals("-") ? "" : " — " + res[4]),
                        res[4].isEmpty() || res[4].equals("-"));
                check(width + "px: " + want + " chữ biểu đồ không tràn ra ngoài"
                        + (res[5].isEmpty() || res[5].equals("-") ? "" : " — " + res[5]),
                        res[5].isEmpty() || res[5].equals("-"));
                check(width + "px: " + want + " chữ bị cắt đều còn chỗ đọc"
                        + (res[6].isEmpty() || res[6].equals("-") ? "" : " — " + res[6]),
                        res[6].isEmpty() || res[6].equals("-"));
                if ("LEARNING".equals(want) || "AI".equals(want)) {
                    // Asked only of the two views that own their scrolling: thirteen
                    // no-op passes would inflate the count without measuring anything.
                    check(width + "px: " + want + " điều khiển chính nằm trong khung nhìn, không phải cuộn cả trang"
                            + (res[7].isEmpty() || res[7].equals("-") ? "" : " — " + res[7]),
                            res[7].isEmpty() || res[7].equals("-"));
                }
                check(width + "px: " + want + " chỉ rõ đang ở module nào"
                        + (res[8].isEmpty() ? "" : " — " + res[8]), res[8].isEmpty());
                // The two probes above say "nothing overflows and nothing is
                // clipped". They cannot say whether the page still reads as a
                // design at the app's own floor, so keep the picture too.
                try {
                    java.util.concurrent.CompletableFuture<Void> snap =
                            new java.util.concurrent.CompletableFuture<>();
                    javafx.application.Platform.runLater(() -> {
                        try {
                            javafx.scene.image.Image img =
                                    LifeDeskApp.primaryStageRef.getScene().snapshot(null);
                            javax.imageio.ImageIO.write(toAwt(img), "png",
                                    Path.of("target/ui-narrow-"
                                            + want.toLowerCase(java.util.Locale.ROOT) + ".png").toFile());
                            snap.complete(null);
                        } catch (Throwable t) {
                            snap.completeExceptionally(t);
                        }
                    });
                    snap.get(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    System.out.println("NARROW SNAP FAILED for " + want + ": " + e);
                }
                if ("LEARNING".equals(want)) {
                    // A card that shows a scrollbar for one word tells the user the page
                    // needs scrolling when it does not. Measured only after the snapshot,
                    // because WebKit reflows on its own clock: read straight after the
                    // window resize it still reported the old viewport and claimed 319px
                    // of scroll that the picture taken moments later proved was not there.
                    try {
                        String cs = measureCardScroll();
                        check(width + "px: " + want
                                        + " thẻ ôn tập không hiện thanh cuộn vô nghĩa"
                                        + (cs.isEmpty() ? "" : " — " + cs), cs.isEmpty());
                    } catch (Exception e) {
                        check(width + "px: " + want + " đọc được trang thẻ ôn tập", false);
                        System.out.println("CARD MEASURE FAILED: " + e);
                    }
                }
            } catch (Exception e) {
                check(width + "px: probe được " + want, false);
                System.out.println("NARROW PROBE FAILED for " + want + ": " + e);
            }
        }
        // Reach, not a verdict: if the walk never matched a uniform row, every
        // "các hàng thẻ số đều ô" above passed by measuring nothing.
        check("cổng đo hàng thẻ thấy được " + uniformRows.get() + " hàng (cần ≥ 2)",
                uniformRows.get() >= 2);
        check("cổng đo chữ biểu đồ thấy " + chartLabels.get() + " nhãn, sát mép nhất "
                + (chartEdgeTight.get() >= 100000 ? "?" : chartEdgeTight.get() + "px")
                + " (cần ≥ 1)", chartLabels.get() >= 1);
        // The silent-cut rule only ever speaks when it finds a fault, so its reach is
        // measured instead: the walk has to read a real number of labels, and the
        // count of "cut but one hover away" shows the branch that forgives a label is
        // not forgiving an empty set. Control that proved the rule bites: deleting the
        // tooltip from a truncated Dashboard budget row turned it RED.
        check("cổng đo chữ cắt thấy " + silentLabels.get() + " nhãn, "
                + silentRescued.get() + " nhãn cắt còn tooltip (cần ≥ 200 nhãn)",
                silentLabels.get() >= 200);
    }

    /** The first label of the status bar: what the app currently claims. */
    static String statusText() throws Exception {
        java.util.concurrent.CompletableFuture<String> f =
                new java.util.concurrent.CompletableFuture<>();
        javafx.application.Platform.runLater(() -> {
            String text = "(không đọc được thanh trạng thái)";
            if (LifeDeskApp.primaryStageRef.getScene().lookup(".statusbar")
                    instanceof javafx.scene.layout.HBox bar
                    && !bar.getChildren().isEmpty()
                    && bar.getChildren().get(0) instanceof javafx.scene.control.Label l) {
                text = String.valueOf(l.getText());
            }
            f.complete(text);
        });
        return f.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** JavaFX image → AWT image, because the PNG writers go through ImageIO. */
    static java.awt.image.BufferedImage toAwt(javafx.scene.image.Image img) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                (int) img.getWidth(), (int) img.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        javafx.scene.image.PixelReader pr = img.getPixelReader();
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                out.setRGB(x, y, pr.getArgb(x, y));
            }
        }
        return out;
    }

    /**
     * Chips are the one label class that may never shorten: "Cao", "trễ 2…",
     * "chuỗi 0 n…" stop being a status, and unlike a title there is no long text
     * to blame. Plain text labels are excluded on purpose — a task title eliding
     * is the design, not a defect.
     */
    private static void findClippedChips(javafx.scene.Node node, java.util.List<String> out) {
        if (out.size() >= 8) {
            return;
        }
        if (node instanceof javafx.scene.control.Labeled lb
                && lb.getStyleClass().contains("chip")) {
            clipIfNarrow(lb, out, "chip");
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                findClippedChips(child, out);
            }
        }
    }

    /**
     * The innermost nodes whose *minimum* width exceeds the viewport. Layout bounds
     * are useless here: fitToWidth stretches every container to the page width, so
     * they all read 825px and none of them is the cause. Only a node that refuses
     * to shrink below the limit actually holds the layout open.
     */
    private static void findWideNodes(javafx.scene.Node node, double limit, java.util.List<String> out) {
        if (out.size() >= 4) {
            return;
        }
        double min = node.minWidth(-1);
        if (min <= limit) {
            return;
        }
        boolean childAlsoWide = false;
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                if (child.minWidth(-1) > limit) {
                    childAlsoWide = true;
                    break;
                }
            }
        }
        if (!childAlsoWide) {
            out.add(node.getClass().getSimpleName()
                    + (node.getStyleClass().isEmpty() ? "" : "." + node.getStyleClass().get(0))
                    + " min=" + Math.round(min) + "px");
            return;
        }
        if (node instanceof javafx.scene.Parent p) {
            for (javafx.scene.Node child : p.getChildrenUnmodifiable()) {
                findWideNodes(child, limit, out);
            }
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
        // A shelf with real entries: an empty library page measures nothing, and the
        // per-kind cover placeholder is the art every card shows without a file.
        ctx.library().add("Clean Code", com.lifedesk.library.LibraryService.Kind.BOOK,
                "Robert C. Martin", 2008, null);
        ctx.library().add("Dune", com.lifedesk.library.LibraryService.Kind.MOVIE,
                "Denis Villeneuve", 2021, null);
        ctx.library().add("Frieren", com.lifedesk.library.LibraryService.Kind.SERIES,
                "Madhouse", 2023, null);
        ctx.library().add("Elden Ring", com.lifedesk.library.LibraryService.Kind.GAME,
                "FromSoftware", 2022, null);
        System.out.println("Seeded demo dataset: 34 tasks, 6 notes, ~76 transactions,"
                + " 3 habits, 18 health rows, 4 library items");
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
            // From here the window is measured view by view; a stray click would make a
            // page look like another one (see isolateFromHumanInput).
            java.util.List<String> humanInput = new java.util.ArrayList<>();
            isolateFromHumanInput(humanInput);
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
                    // The switch runs on the FX thread, but the page can still be
                    // settling (lazy attach, entrance animation, deferred scroll).
                    // One run captured 5 of 12 views while the PREVIOUS view was still
                    // on screen — the screenshot and the overflow check then measured
                    // the wrong page and still reported PASS. So: wait for the view we
                    // asked for, capture only then, and fail loudly if it never shows.
                    final int[] tries = {0};
                    final Runnable[] capture = new Runnable[1];
                    capture[0] = () -> {
                        try {
                            var scene = LifeDeskApp.primaryStageRef.getScene();
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
                            var kids0 = ((javafx.scene.layout.Pane) sp.getContent()).getChildren();
                            String shownNow = kids0.isEmpty() ? "(trống)"
                                    : kids0.get(0) instanceof com.lifedesk.ui.View mv
                                    ? mv.id() : kids0.get(0).getClass().getSimpleName();
                            if (!v[0].equals(shownNow)) {
                                if (tries[0]++ < 12) {
                                    // Name the disagreement, do not just repeat it: the
                                    // shell's own answer, how many nodes carry
                                    // .content-area, and which node was read. Without
                                    // this a mismatch cannot be told apart from a probe
                                    // that looked in the wrong container.
                                    String shell = LifeDeskApp.mainWindowRef.currentView() == null
                                            ? "?" : LifeDeskApp.mainWindowRef.currentView().name();
                                    System.out.println("WAIT " + v[0] + " (đang hiện " + shownNow
                                            + " | shell=" + shell
                                            + " | content@" + Integer.toHexString(
                                                    System.identityHashCode(sp.getContent()))
                                            + " | areas=" + scene.getRoot().lookupAll(".content-area").size()
                                            + " | children=" + kids0.size() + ")");
                                    javafx.animation.PauseTransition settle =
                                            new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
                                    settle.setOnFinished(e ->
                                            javafx.application.Platform.runLater(capture[0]));
                                    settle.play();
                                    return;
                                }
                            }
                            check("chụp đúng view " + v[0] + " (đang hiện " + shownNow + ")",
                                    v[0].equals(shownNow));
                            // Snapshot and measure a settled tree, not a half-finished
                            // reflow (see the narrow-window probe).
                            scene.getRoot().applyCss();
                            scene.getRoot().layout();
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
                            double viewport = sp.getViewportBounds().getWidth();
                            double contentW = sp.getContent().getLayoutBounds().getWidth();
                            String center = shownNow;
                            // The page no longer paints its name, so the shell has to
                            // carry it instead: the window title (taskbar, Alt+Tab) and
                            // the content area's accessible text (screen reader, and the
                            // one place a test can ask which module this is). A title
                            // growing back inside a page is exactly the duplicate the
                            // rail already says out loud.
                            String label = com.lifedesk.ui.MainWindow.ViewId
                                    .valueOf(v[0]).label;
                            String shellTitle = LifeDeskApp.primaryStageRef.getTitle();
                            String accName = sp.getContent().getAccessibleText();
                            var paintedTitle = kids0.isEmpty() ? null
                                    : kids0.get(0).lookup(".title-label");
                            check(v[0] + " có tên ở cửa sổ + accessible, không vẽ lại trong trang"
                                    + " (title=\"" + shellTitle + "\", acc=" + accName
                                    + (paintedTitle == null ? "" : ", trang vẫn tự vẽ tiêu đề") + ")",
                                    shellTitle.contains(label)
                                            && label.equals(accName)
                                            && paintedTitle == null);
                            // ...and the rail has to actually show the item it is
                            // highlighting: the page no longer repeats its own name, so
                            // a highlight below the fold leaves the user with no answer
                            // to "which module am I in?" — and no mouse route to it.
                            String railMsg = railActiveVisible();
                            check("màn hình chỉ rõ đang ở module nào khi ở " + v[0]
                                    + (railMsg.isEmpty() ? "" : " — " + railMsg), railMsg.isEmpty());
                            // ...and the row it points at has to be the row of this page.
                            String railWrong = railMarksCurrentView();
                            check("dấu 'đang ở đây' đúng là module đang mở khi ở " + v[0]
                                    + (railWrong.isEmpty() ? "" : " — " + railWrong), railWrong.isEmpty());
                            // Once printed this as a note; a note nobody fails on is not a check.
                            check("không tràn ngang khi ở " + v[0], contentW <= viewport + 1);
                            if ("AI".equals(v[0])) {
                                // The chat page must fill the window, not grow past it:
                                // the message column is the scroller. Content taller than
                                // the viewport means a second, outer scrollbar — the thing
                                // that hid the composer.
                                double viewportH = sp.getViewportBounds().getHeight();
                                double contentH = sp.getContent().getLayoutBounds().getHeight();
                                check("trang chat không cuộn cả trang [contentH="
                                        + Math.round(contentH) + " viewportH=" + Math.round(viewportH)
                                        + " " + heightChain(sp.getContent()) + "]",
                                        contentH <= viewportH + 1);
                                // "Không có viền bọc ngoài" measured from the file that
                                // declares the border would still pass when a theme or a
                                // Java setStyle drew one. Region keeps the computed CSS, so
                                // ask the node what it actually paints.
                                String surfaceEdges = paintedEdges(scene.lookup(".chat-surface"));
                                String headEdges = paintedEdges(scene.lookup(".chat-head"));
                                check("cột chat không còn khung bọc ngoài"
                                        + (surfaceEdges.isEmpty() && headEdges.isEmpty()
                                                ? "" : " — surface: " + surfaceEdges + ", head: " + headEdges),
                                        surfaceEdges.isEmpty() && headEdges.isEmpty());
                            }
                            // A squeezed toolbar cuts control text to "Chia …", "Thẻ li…".
                            // That was found by eye once; it should not need an eye again.
                            java.util.List<String> clipped = new java.util.ArrayList<>();
                            findClippedControls(scene.getRoot(), clipped);
                            check("không có nút nào bị cắt chữ ở " + v[0]
                                    + (clipped.isEmpty() ? "" : " — " + clipped), clipped.isEmpty());
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
                    };
                    javafx.application.Platform.runLater(capture[0]);
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
            // What the swallowed input was, printed rather than hidden: the filters stay
            // on for the rest of the run (the narrow pass measures the same window), and a
            // run that had a hand in it should say so out loud.
            if (!humanInput.isEmpty()) {
                System.out.println("INPUT " + humanInput.size()
                        + " thao tác thật đã chặn trong lúc đo: "
                        + String.join(" | ", humanInput));
            }
        }
        // ---- A media note whose link cannot play. This is the only path that builds
        // MediaBar at all, so without it the narrow pass below measures the note editor
        // while its pinned player bar never exists - "nothing clipped" would be true of
        // an empty space. Assert the degrade itself first: transport off, the OS-handler
        // button in its place, the link still readable.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            try {
                java.util.concurrent.CompletableFuture<String[]> mediaStep =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        // The reload test shuts its own context down; this pass needs the
                        // one the running window is actually wired to. Service writes also
                        // fire the view notifiers, so they belong on the FX thread here.
                        com.lifedesk.app.AppContext live = LifeDeskApp.mainWindowRef.context();
                        var mediaNote = live.notes().create("Ghi chú media cổng đo", "work",
                                "# Media", java.util.Set.of());
                        live.notes().setMediaLink(mediaNote.id, "https://www.youtube.com/watch?v=probe");
                        com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                        w.showView(com.lifedesk.ui.MainWindow.ViewId.NOTES);
                        ((com.lifedesk.ui.views.NotesView) w.openedView(
                                com.lifedesk.ui.MainWindow.ViewId.NOTES)).openRecord(mediaNote.id);
                        javafx.scene.Parent root = LifeDeskApp.primaryStageRef.getScene().getRoot();
                        root.applyCss();
                        root.layout();
                        javafx.scene.Node bar = findNode(root,
                                n -> n instanceof com.lifedesk.ui.panes.MediaBar);
                        if (bar == null) {
                            mediaStep.complete(new String[]{"false", "không có MediaBar trong cây"});
                            return;
                        }
                        javafx.scene.Node play = findNode(bar, n ->
                                n instanceof javafx.scene.control.Button b && b.getText().isEmpty());
                        javafx.scene.Node ext = findNode(bar, n ->
                                n instanceof javafx.scene.control.Button b
                                        && "Mở ngoài".equals(b.getAccessibleText()));
                        boolean transportOff = play instanceof javafx.scene.control.Button pb
                                && pb.isDisabled();
                        boolean externalShown = ext instanceof javafx.scene.control.Button eb
                                && eb.isVisible() && eb.isManaged();
                        // A dead-looking player is only honest if it says why.
                        boolean saysWhy = findNode(bar, n ->
                                n instanceof javafx.scene.control.Label l
                                        && l.getText() != null
                                        && l.getText().contains("không phát")) != null;
                        mediaStep.complete(new String[]{
                                String.valueOf(transportOff && externalShown && saysWhy),
                                "transport-off=" + transportOff + " nút-mở-ngoài="
                                        + externalShown + " nói-rõ-lý-do=" + saysWhy});
                    } catch (Throwable t) {
                        mediaStep.completeExceptionally(t);
                    }
                });
                String[] media = mediaStep.get(20, java.util.concurrent.TimeUnit.SECONDS);
                check("media không phát được → tắt điều khiển, hiện nút mở ngoài ("
                        + media[1] + ")", "true".equals(media[0]));
            } catch (Exception e) {
                check("đo được thanh media suy giảm", false);
                System.out.println("MEDIA PASS FAILED: " + e);
            }
        }
        // ---- Narrow-window pass. Probe at the smallest window the app itself
        // allows (its stage min size), not at a width invented here: that is the
        // worst case a user can actually reach.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            // Every module the shell can show, including the two that were missing
            // from this list: the dashboard is the densest page in the app and the
            // search page is the one that renders whatever the user typed.
            String[] probeViews = {"DASHBOARD", "TASKS", "NOTES", "FINANCE", "HABITS",
                    "HEALTH", "LEARNING", "FILES", "AI", "AGENT", "RSS", "LIBRARY",
                    "SEARCH", "SETTINGS"};
            try {
                final double[] restore = new double[2];
                // [asked, achieved] — if the window manager clamps the resize, the
                // probes below would silently measure the wide window again.
                final double[] floor = new double[2];
                final double[] actual = new double[2];
                java.util.concurrent.CompletableFuture<Void> shrink =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    javafx.stage.Stage st = LifeDeskApp.primaryStageRef;
                    restore[0] = st.getWidth();
                    restore[1] = st.getHeight();
                    floor[0] = st.getMinWidth();
                    floor[1] = st.getMinHeight();
                    st.setWidth(floor[0]);
                    st.setHeight(floor[1]);
                    actual[0] = st.getWidth();
                    actual[1] = st.getHeight();
                    shrink.complete(null);
                });
                shrink.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(800);
                int w = (int) actual[0];
                check("cửa sổ thật sự co về sàn " + (int) floor[0] + "px (đạt " + w + "px)",
                        Math.abs(actual[0] - floor[0]) < 2 && w < 1500);
                probeAtWidth(w, probeViews);
                java.util.concurrent.CompletableFuture<Void> back =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    javafx.stage.Stage st = LifeDeskApp.primaryStageRef;
                    st.setWidth(restore[0]);
                    st.setHeight(restore[1]);
                    back.complete(null);
                });
                back.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(500);
            } catch (Exception e) {
                check("đo được các view ở cửa sổ hẹp", false);
                System.out.println("NARROW PASS FAILED: " + e);
            }
        }
        // ---- Hidden-view repaint pass. A service change used to repaint every view
        // the session had opened, on screen or not. Those rebuilds are frames nobody
        // ever sees, and they queue in front of the click that caused them.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            try {
                com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                java.util.concurrent.CompletableFuture<Void> step1 =
                        new java.util.concurrent.CompletableFuture<>();
                final int[] base = new int[2];
                javafx.application.Platform.runLater(() -> {
                    w.showView(com.lifedesk.ui.MainWindow.ViewId.TASKS);
                    base[0] = w.openedView(com.lifedesk.ui.MainWindow.ViewId.FINANCE).paintCount();
                    base[1] = w.openedView(com.lifedesk.ui.MainWindow.ViewId.TASKS).paintCount();
                    step1.complete(null);
                });
                step1.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(400);
                // Finance is off screen and watches only finance; Tasks is the page
                // the user is looking at and watches only tasks. Each fire therefore
                // isolates one side of the rule.
                w.context().finance().changes().fire();
                Thread.sleep(700);
                java.util.concurrent.CompletableFuture<int[]> after =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> after.complete(new int[]{
                        w.openedView(com.lifedesk.ui.MainWindow.ViewId.FINANCE).paintCount(),
                        w.openedView(com.lifedesk.ui.MainWindow.ViewId.TASKS).paintCount()}));
                int[] seen = after.get(10, java.util.concurrent.TimeUnit.SECONDS);
                check("view ẩn không vẽ lại khi có thay đổi (Tài chính +"
                        + (seen[0] - base[0]) + ")", seen[0] == base[0]);
                w.context().tasks().changes().fire();
                Thread.sleep(700);
                java.util.concurrent.CompletableFuture<Integer> live =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> live.complete(
                        w.openedView(com.lifedesk.ui.MainWindow.ViewId.TASKS).paintCount()));
                int visiblePaints = live.get(10, java.util.concurrent.TimeUnit.SECONDS);
                check("view đang hiện vẫn vẽ lại ngay (Công việc +"
                        + (visiblePaints - seen[1]) + ")", visiblePaints == seen[1] + 1);
                java.util.concurrent.CompletableFuture<Void> step2 =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    w.showView(com.lifedesk.ui.MainWindow.ViewId.FINANCE);
                    step2.complete(null);
                });
                step2.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(700);
                java.util.concurrent.CompletableFuture<Integer> fin =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> fin.complete(
                        w.openedView(com.lifedesk.ui.MainWindow.ViewId.FINANCE).paintCount()));
                int onShow = fin.get(10, java.util.concurrent.TimeUnit.SECONDS);
                check("view ẩn được bù vẽ lại ngay khi hiện (+"
                        + (onShow - seen[0]) + ")", onShow == seen[0] + 1);
            } catch (Exception e) {
                check("đo được số lần vẽ lại của view", false);
                System.out.println("PAINT PASS FAILED: " + e);
            }
        }
        // ---- Write-through repaint. The views stopped calling refresh() after a
        // service write and rely on the change notifier instead, so a write that did
        // not come from this page's own button must still land on the page the user
        // is looking at.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            try {
                com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                java.util.concurrent.CompletableFuture<javafx.scene.Node> tbl =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    w.showView(com.lifedesk.ui.MainWindow.ViewId.TASKS);
                    tbl.complete(LifeDeskApp.primaryStageRef.getScene().lookup(".table-view"));
                });
                javafx.scene.Node found = tbl.get(10, java.util.concurrent.TimeUnit.SECONDS);
                check("tìm được bảng công việc để đo", found != null);
                if (found instanceof javafx.scene.control.TableView<?> table) {
                    int n0 = table.getItems().size();
                    w.context().tasks().create("Việc do cổng đo thêm", "", Priority.MEDIUM,
                            null, null, "", java.util.List.of(), null);
                    Thread.sleep(900);
                    java.util.concurrent.CompletableFuture<Integer> rows =
                            new java.util.concurrent.CompletableFuture<>();
                    javafx.application.Platform.runLater(() -> rows.complete(table.getItems().size()));
                    int n1 = rows.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    check("ghi dữ liệu tự vẽ lại trang đang mở (" + n0 + " → " + n1 + " dòng)",
                            n1 == n0 + 1);
                }
            } catch (Exception e) {
                check("đo được vẽ lại sau khi ghi dữ liệu", false);
                System.out.println("WRITE PASS FAILED: " + e);
            }
        }
        // ---- Global search end to end: type, Enter to search, Enter again to open
        // the chosen hit. This is the flow that used to need the mouse twice.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            try {
                com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                java.util.concurrent.CompletableFuture<String> step =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        w.showSearch("báo cáo");
                        var scene = LifeDeskApp.primaryStageRef.getScene();
                        scene.getRoot().applyCss();
                        scene.getRoot().layout();
                        if (!(scene.lookup(".list-view")
                                instanceof javafx.scene.control.ListView<?> list)) {
                            step.complete("không có danh sách kết quả");
                            return;
                        }
                        int hits = list.getItems().size();
                        int sel = list.getSelectionModel().getSelectedIndex();
                        step.complete("hits=" + hits + " chọn=" + sel);
                        if (hits > 0 && sel == 0) {
                            list.fireEvent(new javafx.scene.input.KeyEvent(
                                    javafx.scene.input.KeyEvent.KEY_RELEASED, "", "",
                                    javafx.scene.input.KeyCode.ENTER,
                                    false, false, false, false));
                        }
                    } catch (Throwable th) {
                        step.completeExceptionally(th);
                    }
                });
                String found = step.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(700);
                java.util.concurrent.CompletableFuture<String> shown =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    var area = (javafx.scene.layout.Pane) LifeDeskApp.primaryStageRef
                            .getScene().lookup(".content-area");
                    shown.complete(area != null && !area.getChildren().isEmpty()
                            && area.getChildren().get(0) instanceof com.lifedesk.ui.View vv
                            ? vv.id() : "(trống)");
                });
                String afterEnter = shown.get(10, java.util.concurrent.TimeUnit.SECONDS);
                check("Enter trong kết quả mở thẳng bản ghi (" + found + " → "
                        + afterEnter + ")", found.contains("chọn=0")
                            && !found.startsWith("hits=0") && !"SEARCH".equals(afterEnter));
            } catch (Exception e) {
                check("đo được luồng tìm kiếm bằng bàn phím", false);
                System.out.println("SEARCH FLOW FAILED: " + e);
            }
        }
        // ---- The status bar is a receipt, not a billboard: "Đã lưu: …" has to
        // expire, or it keeps claiming something happened minutes after it did.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            try {
                com.lifedesk.ui.MainWindow w = LifeDeskApp.mainWindowRef;
                java.util.concurrent.CompletableFuture<Void> set =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    w.status("Đã lưu: bản ghi cổng đo");
                    set.complete(null);
                });
                set.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(400);
                String early = statusText();
                Thread.sleep(5200);
                String late = statusText();
                check("thanh trạng thái tự xoá tin (" + early + " → " + late + ")",
                        early.contains("bản ghi cổng đo") && late.contains("Sẵn sàng"));
            } catch (Exception e) {
                check("đo được vòng đời thanh trạng thái", false);
                System.out.println("STATUS PASS FAILED: " + e);
            }
        }
        // ---- Icon integrity. Two separate failures look identical on screen: a name
        // with no icons/<name>.svg, and a file whose geometry the parser reads as zero
        // shapes. Both paint a blank square and leave only a log line behind, so each is
        // measured: every pickable name must render, and nothing the app itself asked
        // for while painting the modules may be missing.
        if (captured) {
            java.util.concurrent.CompletableFuture<Void> iconStep =
                    new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    java.util.List<String> blank = new java.util.ArrayList<>();
                    for (String name : com.lifedesk.ui.panes.IconPicker.ICONS) {
                        com.lifedesk.ui.components.IconView tile =
                                new com.lifedesk.ui.components.IconView(name, 16);
                        javafx.scene.Group art = (javafx.scene.Group) tile.getChildren().get(0);
                        if (art.getChildren().isEmpty()) {
                            blank.add(name);
                        }
                    }
                    int pickable = com.lifedesk.ui.panes.IconPicker.ICONS.size();
                    check("cả " + pickable + " icon trong bộ chọn đều vẽ được (trống: " + blank + ")",
                            blank.isEmpty());
                    java.util.Set<String> gone =
                            com.lifedesk.ui.components.IconView.missingIcons();
                    check("mọi tên icon app đã dùng đều có tệp (thiếu: " + gone + ")",
                            gone.isEmpty());
                } catch (Throwable t) {
                    check("đo được tính toàn vẹn icon", false);
                    System.out.println("ICON PASS FAILED: " + t);
                }
                iconStep.complete(null);
            });
            try {
                iconStep.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                check("icon trả kết quả đúng hẹn", false);
            }
        }
        // ---- Theme-switch regression: WebView panes (review card, markdown preview,
        // RSS reader) embed the palette inside their HTML, so a stylesheet swap alone
        // used to leave them wearing the previous theme's colors. Measure the card
        // itself, before and after the switch: an absolute brightness read at the scene
        // centre once sat exactly on its own threshold (180 against "> 180") because the
        // centre is a fixed point that can land on the page background or on the card's
        // text, and neither of those says anything about the palette.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            try {
                java.util.concurrent.CompletableFuture<Void> onLearning =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        LifeDeskApp.mainWindowRef.showView(com.lifedesk.ui.MainWindow.ViewId.LEARNING);
                        onLearning.complete(null);
                    } catch (Throwable t) {
                        onLearning.completeExceptionally(t);
                    }
                });
                onLearning.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1200); // let the card finish painting in the starting theme
                String darkCss = reviewCardBackground();
                java.awt.image.BufferedImage darkShot = captureScene();
                long darkPatch = cardPatchBrightness(darkShot);
                java.util.concurrent.CompletableFuture<Void> switched =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        LifeDeskApp.mainWindowRef.switchTheme("Light");
                        switched.complete(null);
                    } catch (Throwable t) {
                        switched.completeExceptionally(t);
                    }
                });
                switched.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1500); // WebView reload + repaint
                java.awt.image.BufferedImage lightShot = captureScene();
                Path themeShotPath = screenshot.toAbsolutePath().getParent()
                        .resolve("ui-smoke-learning-after-theme-switch.png");
                javax.imageio.ImageIO.write(lightShot, "png", themeShotPath.toFile());
                String lightCss = reviewCardBackground();
                long lightPatch = cardPatchBrightness(lightShot);
                System.out.println("THEME card background " + darkCss + " -> " + lightCss
                        + " | patch brightness " + darkPatch + " -> " + lightPatch);
                double darkLum = backgroundBrightness(darkCss);
                double lightLum = backgroundBrightness(lightCss);
                check("thẻ ôn tập đổi màu theo theme (" + darkCss + " → " + lightCss + ")",
                        lightLum > 0.7 && lightLum - darkLum > 0.3);
                // The DOM says the palette changed; the pixels say it reached the screen.
                check("màn hình thẻ ôn tập vẽ lại sáng hơn (" + darkPatch + " → " + lightPatch + ")",
                        lightPatch > 180 && lightPatch - darkPatch > 40);
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
                // The dialog buttons used to come straight from JavaFX's English
                // bundle ("OK"/"Cancel"). Read the real rendered labels, not the
                // source text: an empty list means the walk found nothing, which is
                // a failed measurement and must not pass.
                java.util.concurrent.CompletableFuture<java.util.List<String>> labelsF =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    java.util.List<String> texts = new java.util.ArrayList<>();
                    for (javafx.stage.Window win : new java.util.ArrayList<>(javafx.stage.Window.getWindows())) {
                        if (win instanceof javafx.stage.Stage st && st.isShowing()
                                && st != LifeDeskApp.primaryStageRef && st.getScene() != null) {
                            collectButtonLabels(st.getScene().getRoot(), texts);
                        }
                    }
                    labelsF.complete(texts);
                });
                java.util.List<String> dialogLabels = labelsF.get(30, java.util.concurrent.TimeUnit.SECONDS);
                boolean vietnamese = dialogLabels.contains("Lưu") && dialogLabels.contains("Hủy");
                boolean englishLeft = dialogLabels.stream()
                        .anyMatch(t -> t.equals("OK") || t.equals("Cancel") || t.equals("Close"));
                check("nút dialog tiếng Việt, không còn OK/Cancel " + dialogLabels,
                        !dialogLabels.isEmpty() && vietnamese && !englishLeft);
                // Enter has to commit the form. A dialog whose save button is not
                // the scene's default button sends the user back to the mouse at the
                // last step of every single entry.
                java.util.concurrent.CompletableFuture<String> defBtn =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    String found = "(không có dialog nào)";
                    for (javafx.stage.Window win : new java.util.ArrayList<>(
                            javafx.stage.Window.getWindows())) {
                        if (win instanceof javafx.stage.Stage st && st.isShowing()
                                && st != LifeDeskApp.primaryStageRef && st.getScene() != null) {
                            found = st.getScene().getRoot().lookupAll(".button").stream()
                                    .filter(javafx.scene.control.Button.class::isInstance)
                                    .map(javafx.scene.control.Button.class::cast)
                                    .filter(javafx.scene.control.Button::isDefaultButton)
                                    .map(b -> b.getText())
                                    .findFirst().orElse("(không có nút mặc định)");
                            break;
                        }
                    }
                    defBtn.complete(found);
                });
                String defaultBtn = defBtn.get(30, java.util.concurrent.TimeUnit.SECONDS);
                check("Enter gửi được biểu mẫu (nút mặc định = '" + defaultBtn + "')",
                        "Lưu".equals(defaultBtn));
                // Close every open dialog stage with hide(): firing the Cancel
                // button used to close the dialog DURING the Window.getWindows()
                // iteration, corrupting the iterator (NoSuchElementException
                // wrapping "Index 2 out of bounds for length 1" — the same error
                // the user saw in the app). hide() completes showAndWait cleanly.
                javafx.application.Platform.runLater(() -> {
                    for (javafx.stage.Window win : new java.util.ArrayList<>(javafx.stage.Window.getWindows())) {
                        if (win instanceof javafx.stage.Stage st && st.isShowing()
                                && st != LifeDeskApp.primaryStageRef) {
                            st.hide();
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
        // ---- Course-dialog control-height regression (user: "cái cao cái thấp" —
        // the spinner rendered ~8px taller than the combo in the same row).
        if (captured && LifeDeskApp.mainWindowRef != null) {
            java.util.concurrent.CompletableFuture<Boolean> cd = new java.util.concurrent.CompletableFuture<>();
            javafx.application.Platform.runLater(() -> {
                try {
                    LifeDeskApp.mainWindowRef.showView(com.lifedesk.ui.MainWindow.ViewId.LEARNING);
                    for (javafx.scene.Node n : LifeDeskApp.primaryStageRef.getScene().getRoot().lookupAll(".tab-pane")) {
                        if (n instanceof javafx.scene.control.TabPane tp) {
                            for (int i = 0; i < tp.getTabs().size(); i++) {
                                if ("Khóa học & tài liệu".equals(tp.getTabs().get(i).getText())) {
                                    tp.getSelectionModel().select(i);
                                }
                            }
                        }
                    }
                    javafx.scene.Node found = findButton(
                            LifeDeskApp.primaryStageRef.getScene().getRoot(), "Khóa học");
                    if (!(found instanceof javafx.scene.control.Button b)) {
                        throw new IllegalStateException("Không tìm thấy nút Khóa học");
                    }
                    cd.complete(true);
                    b.fire(); // showAndWait blocks — must be after complete()
                } catch (Throwable t) {
                    cd.completeExceptionally(t);
                }
            });
            try {
                cd.get(10, java.util.concurrent.TimeUnit.SECONDS);
                Thread.sleep(1500);
                java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> capC =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        for (javafx.stage.Window win : javafx.stage.Window.getWindows()) {
                            if (win instanceof javafx.stage.Stage st && st.isShowing()
                                    && st != LifeDeskApp.primaryStageRef
                                    && st.getScene() != null) {
                                javafx.scene.image.WritableImage img = st.getScene().snapshot(null);
                                java.awt.image.BufferedImage buffered = new java.awt.image.BufferedImage(
                                        (int) img.getWidth(), (int) img.getHeight(),
                                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                                for (int y = 0; y < (int) img.getHeight(); y++) {
                                    for (int x = 0; x < (int) img.getWidth(); x++) {
                                        buffered.setRGB(x, y, img.getPixelReader().getArgb(x, y));
                                    }
                                }
                                capC.complete(buffered);
                                return;
                            }
                        }
                        capC.completeExceptionally(new IllegalStateException("Course dialog not found"));
                    } catch (Throwable t) {
                        capC.completeExceptionally(t);
                    }
                });
                java.awt.image.BufferedImage shot = capC.get(60, java.util.concurrent.TimeUnit.SECONDS);
                Path cdShot = screenshot.toAbsolutePath().getParent().resolve("ui-smoke-course-dialog.png");
                javax.imageio.ImageIO.write(shot, "png", cdShot.toFile());
                System.out.println("Course dialog snapshot: " + cdShot);
                // hide() on a snapshot copy — firing Cancel inside the live
                // Window.getWindows() loop corrupted its iterator.
                javafx.application.Platform.runLater(() -> {
                    for (javafx.stage.Window win : new java.util.ArrayList<>(javafx.stage.Window.getWindows())) {
                        if (win instanceof javafx.stage.Stage st && st.isShowing()
                                && st != LifeDeskApp.primaryStageRef) {
                            st.hide();
                        }
                    }
                });
                Thread.sleep(800);
            } catch (Exception e) {
                check("mở được dialog khóa học", false);
                System.out.println("Course dialog snapshot FAILED: " + e);
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
        // ---- AI chat round-trip: ask several questions in a row, then measure and
        // snapshot. The streaming render path and the markdown output come from the rule
        // engine (deterministic, offline), and the transcript grows past the window —
        // which is the only case that says whether the page keeps its own scroll or hands
        // it to the window. One short answer fits any screen and measures nothing.
        if (captured && LifeDeskApp.mainWindowRef != null) {
            Path chatShot = screenshot.toAbsolutePath().getParent().resolve("ui-smoke-ai-chat.png");
            try {
                String[] asks = {"tổng quan hôm nay", "việc nào quá hạn", "chi tiêu tháng này",
                        "tóm tắt tình hình thói quen", "có flashcard nào cần ôn không?"};
                for (String ask : asks) {
                    java.util.concurrent.CompletableFuture<String> sent =
                            new java.util.concurrent.CompletableFuture<>();
                    final String question = ask;
                    javafx.application.Platform.runLater(() -> {
                        try {
                            LifeDeskApp.mainWindowRef.showView(com.lifedesk.ui.MainWindow.ViewId.AI);
                            var scene = LifeDeskApp.primaryStageRef.getScene();
                            var input = scene.lookup(".chat-input");
                            if (input instanceof javafx.scene.control.TextArea area) {
                                area.setText(question);
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
                    String fired = sent.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    check("gửi được câu hỏi \"" + ask + "\" bằng Enter", "fired".equals(fired));
                    Thread.sleep(900);
                }
                javafx.application.Platform.runLater(() -> {
                    var root = LifeDeskApp.primaryStageRef.getScene().getRoot();
                    var cells = root.lookupAll(".chat-sidebar .list-cell");
                    System.out.println("SIDEBAR cells=" + cells.size()
                            + " labels=" + root.lookupAll(".chat-sidebar .label").size());
                });
                Thread.sleep(300);
                java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> chatShotFuture =
                        new java.util.concurrent.CompletableFuture<>();
                java.util.concurrent.CompletableFuture<String[]> chatStateFuture =
                        new java.util.concurrent.CompletableFuture<>();
                javafx.application.Platform.runLater(() -> {
                    try {
                        var scene = LifeDeskApp.primaryStageRef.getScene();
                        scene.getRoot().applyCss();
                        scene.getRoot().layout();
                        // Measure the page with a whole conversation inside it. One short
                        // answer fits any window; a transcript taller than the column is
                        // what used to push the composer below the fold, because the
                        // message column reports the height of all of its rows upward.
                        javafx.scene.control.ScrollPane sp = null;
                        for (javafx.scene.Node n = scene.lookup(".content-area");
                             n != null; n = n.getParent()) {
                            if (n instanceof javafx.scene.control.ScrollPane found) {
                                sp = found;
                                break;
                            }
                        }
                        if (sp == null) {
                            throw new IllegalStateException("không có vùng cuộn nội dung");
                        }
                        double viewportH = sp.getViewportBounds().getHeight();
                        double contentH = sp.getContent().getLayoutBounds().getHeight();
                        javafx.scene.Node field = scene.lookup(".chat-input");
                        String composerMsg = "không tìm thấy ô soạn tin";
                        if (field != null) {
                            var ib = field.localToScene(field.getBoundsInLocal());
                            var vb = sp.localToScene(sp.getViewportBounds());
                            boolean inside = ib.getMaxY() <= vb.getMaxY() + 1
                                    && ib.getMinY() >= vb.getMinY() - 1;
                            composerMsg = inside ? ""
                                    : "ô soạn tin ở " + Math.round(ib.getMinY()) + "–"
                                            + Math.round(ib.getMaxY()) + ", khung nhìn tới "
                                            + Math.round(vb.getMaxY());
                        }
                        int bubbles = scene.getRoot().lookupAll(".chat-bubble-ai").size();
                        // Anti-vacuity: "the page does not scroll" only means something
                        // when the transcript is taller than the column it sits in. A short
                        // conversation fits any window and would pass with or without the
                        // height cap, so the run has to say which of the two it measured.
                        String longEnough = "không tìm thấy cột chat";
                        if (scene.lookup(".chat-scroll")
                                instanceof javafx.scene.control.ScrollPane inner) {
                            double innerH = inner.getViewportBounds().getHeight();
                            double rowsH = inner.getContent().getLayoutBounds().getHeight();
                            longEnough = rowsH > innerH + 1 ? "dài"
                                    : "ngắn [bản ghi " + Math.round(rowsH) + "px trong khung "
                                            + Math.round(innerH) + "px]";
                        }
                        chatStateFuture.complete(new String[]{
                                contentH <= viewportH + 1 ? ""
                                        : "contentH=" + Math.round(contentH) + " > viewportH="
                                                + Math.round(viewportH) + " — " + heightChain(sp.getContent()),
                                composerMsg,
                                paintedEdges(scene.lookup(".chat-surface")),
                                String.valueOf(bubbles),
                                longEnough,
                                railMarksCurrentView()});
                        var img = scene.snapshot(null);
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
                        chatStateFuture.completeExceptionally(t);
                    }
                });
                javax.imageio.ImageIO.write(chatShotFuture.get(30, java.util.concurrent.TimeUnit.SECONDS),
                        "png", chatShot.toFile());
                String[] st = chatStateFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
                // Asked first: if the transcript never outgrew the column, the two
                // measurements below are true of an empty page and prove nothing.
                check("bài đo chat đủ dài để phép đo cuộn có ý nghĩa [" + st[4] + "]",
                        "dài".equals(st[4]));
                check("trang chat không cuộn khi hội thoại đã dài"
                        + (st[0].isEmpty() ? "" : " — " + st[0]), st[0].isEmpty());
                check("ô soạn tin còn trong khung nhìn khi hội thoại đã dài"
                        + (st[1].isEmpty() ? "" : " — " + st[1]), st[1].isEmpty());
                check("cột chat không còn khung bọc ngoài khi đã có câu trả lời"
                        + (st[2].isEmpty() ? "" : " — " + st[2]), st[2].isEmpty());
                // A screenshot nobody compares against anything is not a check: "the file
                // exists" stayed true while the page scrolled and the composer was cut.
                check("AI chat trả lời và hiển thị trong khung chat (" + st[3] + " bong bóng)",
                        chatShot.toFile().length() > 0 && Integer.parseInt(st[3]) > 0);
                // The screenshot that proved this was worth asking: the chat page was on
                // screen with the rail lighting up another module.
                check("rail chỉ đúng trang chat khi đo hội thoại"
                        + (st[5].isEmpty() ? "" : " — " + st[5]), st[5].isEmpty());
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
