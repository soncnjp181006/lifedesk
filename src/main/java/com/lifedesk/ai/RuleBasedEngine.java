package com.lifedesk.ai;

import com.lifedesk.finance.FinanceService;
import com.lifedesk.habits.Habit;
import com.lifedesk.habits.HabitService;
import com.lifedesk.learning.LearningService;
import com.lifedesk.markdown.MarkdownService;
import com.lifedesk.notes.NoteMeta;
import com.lifedesk.notes.NotesService;
import com.lifedesk.search.SearchGroup;
import com.lifedesk.search.SearchService;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.Task;
import com.lifedesk.tasks.TaskService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline rule-based assistant. It NEVER pretends to be an LLM: it matches
 * explicit command patterns (Vietnamese + English) and answers from real
 * local data. Unknown input gets an honest "out of capability" reply with
 * the list of supported commands. No network access at all.
 */
public class RuleBasedEngine implements AiProvider {
    public static final String ID = "rule-based";
    private static final Pattern CREATE_TASK = Pattern.compile(
            "(?:tạo|thêm)\\s+(?:task|công việc)\\s*[:,]?\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUMMARIZE_NOTE = Pattern.compile(
            "(?:tóm tắt|summarize)\\s+(?:ghi chú\\s*)?[:,]?\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEARCH_CMD = Pattern.compile(
            "(?:tìm|search|tìm kiếm)\\s*[:,]?\\s*(.+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DUE_SUFFIX = Pattern.compile("\\b(?:hạn|hạn hoàn thành|due)\\s*[:]?\\s*(hôm nay|mai|ngày mai|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);

    private final TaskService taskService;
    private final NotesService notesService;
    private final FinanceService financeService;
    private final HabitService habitService;
    private final LearningService learningService;
    private final SearchService searchService;
    private final MarkdownService markdownService;
    private final com.lifedesk.health.HealthService healthService;

    public RuleBasedEngine(TaskService taskService, NotesService notesService, FinanceService financeService,
                           HabitService habitService, LearningService learningService,
                           SearchService searchService, MarkdownService markdownService,
                           com.lifedesk.health.HealthService healthService) {
        this.taskService = taskService;
        this.notesService = notesService;
        this.financeService = financeService;
        this.habitService = habitService;
        this.learningService = learningService;
        this.searchService = searchService;
        this.markdownService = markdownService;
        this.healthService = healthService;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Rule-based engine (offline, không cần LLM)";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public List<ModelInfo> listModels() {
        return List.of(new ModelInfo("rule-engine-1.0", ID, "máy trạng thái quy tắc, chạy hoàn toàn cục bộ"));
    }

    @Override
    public boolean supportsModelListing() {
        // The rule engine has no selectable models; advertising one made the model
        // browser show "rule-engine-1.0" as if it had been fetched from a provider.
        return false;
    }

    @Override
    public String chat(List<ChatMessage> messages, String model) {
        String lastUser = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role().equals("user")) {
                lastUser = messages.get(i).content();
                break;
            }
        }
        return handle(lastUser);
    }

    /**
     * The rule engine already has the full answer before it returns, so streaming
     * is purely presentational: release it in word groups so the chat surface
     * shows text arriving instead of a wall of text appearing in one frame.
     * Deltas are emitted on the caller's background thread; the view throttles
     * them, so this loop must stay cheap.
     */
    @Override
    public String chatStream(List<ChatMessage> messages, String model,
                             java.util.function.Consumer<String> onDelta) {
        String reply = chat(messages, model);
        if (reply == null || reply.isEmpty()) {
            onDelta.accept("");
            return reply;
        }
        String[] words = reply.split("(?<=\\s)");
        StringBuilder chunk = new StringBuilder();
        int inChunk = 0;
        for (String word : words) {
            chunk.append(word);
            if (++inChunk < 3) {
                continue;
            }
            onDelta.accept(chunk.toString());
            chunk.setLength(0);
            inChunk = 0;
            try {
                Thread.sleep(14);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (chunk.length() > 0) {
            onDelta.accept(chunk.toString());
        }
        return reply;
    }

    /**
     * One assistant capability. Adding an intent = appending one entry here:
     * a match predicate on the raw input, a handler for it, and (optionally)
     * the help bullet it contributes to {@link #help()} — nothing else to edit.
     */
    private record Intent(String helpLine,
                          java.util.function.Predicate<String> matches,
                          java.util.function.UnaryOperator<String> handle) {
    }

    /** First capture group of a pattern already known to match {@code q}. */
    private static String group(String q, Pattern pattern) {
        Matcher m = pattern.matcher(q);
        m.find();
        return m.group(1);
    }

    /** Dispatch order matters: explicit regex commands win over keyword matches. */
    private final List<Intent> intents = List.of(
            new Intent("- \"tạo task: <tiêu đề>\" — tạo công việc mới (hỗ trợ \"hạn mai\", \"hạn 2026-01-31\", \"hạn 31/12\")",
                    q -> CREATE_TASK.matcher(q).find(),
                    q -> createTask(group(q, CREATE_TASK))),
            new Intent("- \"tóm tắt ghi chú: <tên>\" — tóm tắt rút trích từ nội dung ghi chú",
                    q -> SUMMARIZE_NOTE.matcher(q).find(),
                    q -> summarizeNote(group(q, SUMMARIZE_NOTE))),
            new Intent("- \"tìm: <từ khóa>\" — tìm kiếm xuyên module",
                    q -> SEARCH_CMD.matcher(q).find() && !q.toLowerCase(Locale.ROOT).contains("tìm kiếm model"),
                    q -> doSearch(group(q, SEARCH_CMD))),
            new Intent(null,
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("tro giup") || lower.contains("help") || lower.contains("lam duoc gi")
                                || lower.equals("?") || lower.contains("huong dan");
                    },
                    q -> help()),
            // Must sit above the "hôm nay" intent: "tổng quan hôm nay" is a briefing,
            // not a task list, and the plain keyword would otherwise swallow it.
            new Intent("- \"tổng quan hôm nay\" — bản tin một trang: việc gấp, thói quen, tiền, ôn tập",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("tong quan") || lower.contains("tinh hinh") || lower.contains("briefing")
                                || lower.contains("sang nay") || lower.contains("dau ngay")
                                || lower.contains("bat dau tu dau") || lower.contains("uu tien gi")
                                || lower.contains("lam gi truoc") || lower.contains("the nao roi");
                    },
                    q -> briefing()),
            new Intent("- \"việc hôm nay\" / \"today\" — danh sách công việc đến hạn hôm nay",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("hom nay") || lower.contains("today") || lower.contains("viec hom nay");
                    },
                    q -> tasksDueToday()),
            new Intent("- \"quá hạn\" / \"overdue\" — công việc trễ hạn",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("qua han") || lower.contains("overdue");
                    },
                    q -> tasksOverdue()),
            new Intent("- \"sắp tới\" — lịch 7 ngày tới",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("sap toi") || lower.contains("upcoming") || lower.contains("lich sap toi");
                    },
                    q -> upcoming()),
            new Intent("- \"chi tiêu tháng này\" — tổng hợp thu chi",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("chi tieu") || lower.contains("thu chi") || lower.contains("tai chinh")
                                || lower.contains("finance") || lower.contains("expense");
                    },
                    q -> financeSummary()),
            new Intent("- \"thói quen\" — chuỗi ngày và tỷ lệ hoàn thành",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("thoi quen") || lower.contains("habit") || lower.contains("streak");
                    },
                    q -> habitStatus()),
            new Intent("- \"mục tiêu\" — tiến độ mục tiêu",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("muc tieu") || lower.contains("goal");
                    },
                    q -> goalStatus()),
            new Intent("- \"ôn tập\" — số flashcard đến hạn",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("on tap") || lower.contains("flashcard") || lower.contains("hoc tap")
                                || lower.contains("review") || lower.contains("learning");
                    },
                    q -> learningStatus()),
            new Intent("- \"gợi ý\" — đề xuất việc cần làm",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("goi y") || lower.contains("suggest") || lower.contains("nen lam gi");
                    },
                    q -> suggestions()),
            new Intent("- \"sức khỏe\" — trung bình giấc ngủ/vận động/cân nặng",
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("suc khoe") || lower.contains("health") || lower.contains("can nang");
                    },
                    q -> healthStatus()),
            new Intent(null,
                    q -> {
                        String lower = com.lifedesk.common.TextSearch.normalize(q);
                        return lower.contains("tom tat") || lower.contains("summary");
                    },
                    q -> "Để tóm tắt, hãy dùng lệnh: \"tóm tắt ghi chú <tên ghi chú>\"."));

    /** Dispatch one user utterance. Public so the smoke test can exercise it. */
    public String handle(String input) {
        if (input == null || input.isBlank()) {
            return help();
        }
        String q = input.trim();
        for (Intent intent : intents) {
            if (intent.matches().test(q)) {
                return intent.handle().apply(q);
            }
        }
        return unknown(q);
    }

    private String help() {
        StringBuilder sb = new StringBuilder(
                "## Tôi hiểu những lệnh này\n\n"
                        + "_Engine quy tắc chạy hoàn toàn offline — không phải LLM._\n\n");
        for (Intent intent : intents) {
            if (intent.helpLine() != null) {
                sb.append(intent.helpLine()).append('\n');
            }
        }
        sb.append("\nMuốn hỏi đáp ngôn ngữ tự nhiên? Bật provider Ollama hoặc Kilo trong Cài đặt → AI.");
        return sb.toString();
    }

    /**
     * Honest failure, but not a dead end: the offline engine cannot reason about
     * arbitrary language, so instead of just refusing it looks the words up across
     * the user's own data and offers the commands closest to what was asked.
     */
    private String unknown(String q) {
        StringBuilder sb = new StringBuilder();
        sb.append("Câu hỏi \"").append(q)
                .append("\" nằm ngoài khả năng của engine quy tắc (tôi không đoán hay suy luận ngôn ngữ tự nhiên).\n");
        var groups = searchService.query(q, null, 3);
        if (!groups.isEmpty()) {
            sb.append("\n**Có thể bạn đang tìm trong dữ liệu của mình:**\n");
            int shown = 0;
            for (SearchGroup g : groups) {
                for (var r : g.results()) {
                    if (shown++ >= 4) {
                        break;
                    }
                    sb.append("- ").append(r.doc().title())
                            .append(" _(").append(g.displayType()).append(")_\n");
                }
                if (shown >= 4) {
                    break;
                }
            }
        }
        sb.append("\nGõ \"help\" để xem các lệnh hỗ trợ, hoặc bật Ollama/Kilo trong Cài đặt → AI để dùng LLM.");
        return sb.toString();
    }

    private String createTask(String rest) {
        String title = rest.trim();
        LocalDate due = null;
        Matcher dueM = DUE_SUFFIX.matcher(title);
        if (dueM.find()) {
            due = parseDue(dueM.group(1));
            if (due != null) {
                title = (title.substring(0, dueM.start()) + title.substring(dueM.end())).trim();
            }
        }
        if (title.isBlank()) {
            return "Tiêu đề công việc đang trống. Ví dụ: \"tạo task: viết báo cáo hạn mai\"";
        }
        Task t = taskService.create(title, null, Priority.MEDIUM, due, null, "", List.of(), null);
        return "Đã tạo công việc: \"" + t.title + "\""
                + (due != null ? " (hạn " + due.format(DateTimeFormatter.ISO_DATE) + ")" : " (không đặt hạn)");
    }

    private LocalDate parseDue(String text) {
        return switch (com.lifedesk.common.TextSearch.normalize(text)) {
            case "hom nay" -> LocalDate.now();
            case "mai", "ngay mai" -> LocalDate.now().plusDays(1);
            default -> parseDate(text);
        };
    }

    private LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
            // fall through to dd/MM
        }
        try {
            String[] parts = text.trim().split("/");
            if (parts.length == 2) {
                return LocalDate.of(LocalDate.now().getYear(), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
            }
        } catch (RuntimeException ignored) {
            // not a date, leave null
        }
        return null;
    }

    /** Việt hoá mức ưu tiên cho câu trả lời (chat hiển thị thẳng chuỗi này). */
    private static String priorityLabel(Priority p) {
        return switch (p) {
            case URGENT -> "Khẩn";
            case HIGH -> "Ưu tiên cao";
            case MEDIUM -> "Trung bình";
            case LOW -> "Thấp";
        };
    }

    /** Most urgent first: priority, then due time, then creation order. */
    private static int byUrgency(Task a, Task b) {
        int p = Integer.compare(b.priority.ordinal(), a.priority.ordinal());
        if (p != 0) {
            return p;
        }
        java.time.LocalTime ta = a.dueTime;
        java.time.LocalTime tb = b.dueTime;
        if (ta != null && tb != null) {
            int t = ta.compareTo(tb);
            if (t != 0) {
                return t;
            }
        } else if (ta != null) {
            return -1;
        } else if (tb != null) {
            return 1;
        }
        return a.title.compareToIgnoreCase(b.title);
    }

    private static String vnd(java.math.BigDecimal amount) {
        return String.format("%,d", amount.longValue()).replace(',', '.') + " ₫";
    }

    private String tasksDueToday() {
        List<Task> list = new ArrayList<>(taskService.dueToday());
        if (list.isEmpty()) {
            return "## Hôm nay không có việc đến hạn\n\n"
                    + "Không có gì gấp trong hôm nay. Nếu muốn đi trước, hỏi tôi \"sắp tới\" "
                    + "để xem 7 ngày tới.";
        }
        list.sort(RuleBasedEngine::byUrgency);
        StringBuilder sb = new StringBuilder("## Việc đến hạn hôm nay\n\n")
                .append("**").append(list.size()).append(" việc**, xếp theo mức độ gấp:\n\n");
        int index = 1;
        for (Task t : list) {
            sb.append(index++).append(". ").append(t.title)
                    .append(" — _").append(priorityLabel(t.priority)).append('_');
            if (t.dueTime != null) {
                sb.append(", ").append(t.dueTime);
            }
            if (!t.group.isBlank()) {
                sb.append(" · ").append(t.group);
            }
            sb.append('\n');
        }
        Task first = list.get(0);
        sb.append("\n**Bắt đầu với:** ").append(first.title)
                .append(" (_").append(priorityLabel(first.priority)).append("_) — xong việc khó nhất trước.");
        return sb.toString();
    }

    private String tasksOverdue() {
        List<Task> list = new ArrayList<>(taskService.overdue());
        if (list.isEmpty()) {
            return "## Không có việc quá hạn\n\nSạch nợ công việc. Giữ nguyên đà này nhé.";
        }
        list.sort(java.util.Comparator.comparing(t -> t.dueDate));
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder("## Việc quá hạn\n\n**" + list.size() + " việc** cần xử lý:\n\n");
        for (Task t : list) {
            long days = t.dueDate == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(t.dueDate, today);
            sb.append("- ").append(t.title).append(" — hạn ").append(t.dueDate)
                    .append(" (_").append(days).append(" ngày_)")
                    .append(" · ").append(priorityLabel(t.priority)).append('\n');
        }
        sb.append("\nNếu việc nào không còn cần nữa, hãy đóng nó lại thay vì để nó tiếp tục đè nặng danh sách.");
        return sb.toString();
    }

    private String upcoming() {
        List<Task> list = new ArrayList<>(taskService.upcoming(7));
        if (list.isEmpty()) {
            return "## 7 ngày tới trống lịch\n\nChưa có công việc nào được đặt hạn trong tuần tới.";
        }
        list.sort(java.util.Comparator.comparing((Task t) -> t.dueDate).thenComparing(RuleBasedEngine::byUrgency));
        StringBuilder sb = new StringBuilder("## Lịch 7 ngày tới\n\n");
        LocalDate lastDate = null;
        for (Task t : list) {
            if (!t.dueDate.equals(lastDate)) {
                lastDate = t.dueDate;
                sb.append("\n### ").append(lastDate)
                        .append(" (_").append(lastDate.getDayOfWeek().getDisplayName(
                                java.time.format.TextStyle.SHORT, java.util.Locale.forLanguageTag("vi-VN")))
                        .append("_)\n\n");
            }
            sb.append("- ").append(t.title);
            if (t.dueTime != null) {
                sb.append(", ").append(t.dueTime);
            }
            sb.append(" · ").append(priorityLabel(t.priority)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String financeSummary() {
        var now = java.time.YearMonth.now();
        var s = financeService.monthSummary(now);
        String verdict = s.net().signum() >= 0
                ? "Tháng này bạn đang giữ lại được **" + vnd(s.net()) + "**."
                : "Tháng này chi đang vượt thu **" + vnd(s.net().abs()) + "** — nên siết lại các khoản lớn.";
        return "## Tài chính tháng " + now.getMonthValue() + "/" + now.getYear() + "\n\n"
                + "| Khoản | Số tiền |\n"
                + "|---|---:|\n"
                + "| Thu nhập | " + vnd(s.income()) + " |\n"
                + "| Chi tiêu | " + vnd(s.expense()) + " |\n"
                + "| **Ròng** | **" + vnd(s.net()) + "** |\n\n"
                + verdict;
    }

    private String habitStatus() {
        List<Habit> habits = habitService.habits(false);
        if (habits.isEmpty()) {
            return "## Chưa có thói quen nào\n\nTạo một thói quen nhỏ trong mục Thói quen & Mục tiêu "
                    + "để tôi theo dõi chuỗi ngày giúp bạn.";
        }
        StringBuilder sb = new StringBuilder("## Thói quen hiện tại\n\n")
                .append("| Thói quen | Chuỗi | 30 ngày |\n")
                .append("|---|---:|---:|\n");
        for (Habit h : habits) {
            sb.append("| ").append(h.name).append(" | ").append(habitService.currentStreak(h))
                    .append(" ngày | ").append(Math.round(habitService.completionRate(h, 30) * 100))
                    .append("% |\n");
        }
        return sb.toString().stripTrailing();
    }

    private String goalStatus() {
        var goals = habitService.goals();
        var active = goals.stream().filter(g -> g.status() == com.lifedesk.habits.GoalStatus.ACTIVE).toList();
        if (active.isEmpty()) {
            return "## Không có mục tiêu đang hoạt động\n\nĐặt một mục tiêu trong Thói quen & Mục tiêu "
                    + "để tôi báo tiến độ mỗi khi bạn hỏi.";
        }
        StringBuilder sb = new StringBuilder("## Mục tiêu đang hoạt động\n\n");
        for (var g : active) {
            sb.append("- **").append(g.title()).append("** — ").append(
                    g.progressMode() == com.lifedesk.habits.ProgressMode.MANUAL
                            ? g.manualProgress() + "% (tự đặt)"
                            : "tự động từ công việc liên kết")
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String learningStatus() {
        int due = learningService.dueCount();
        var courses = learningService.courses().stream()
                .filter(c -> c.status() == com.lifedesk.learning.CourseStatus.IN_PROGRESS).toList();
        StringBuilder sb = new StringBuilder("## Học tập\n\n");
        sb.append("- Flashcard đến hạn ôn: **").append(due).append("**\n");
        sb.append("- Khóa học đang học: **").append(courses.size()).append("**\n");
        for (var c : courses) {
            sb.append("  - ").append(c.title()).append(" (_").append(c.progressPercent()).append("%_)\n");
        }
        if (due == 0 && courses.isEmpty()) {
            sb.append("\nHiện không có gì để ôn — thêm thẻ hoặc khóa học mới trong mục Học tập.");
        }
        return sb.toString().stripTrailing();
    }

    private String healthStatus() {
        Double sleep = healthService.averageDays(com.lifedesk.health.HealthService.HealthField.SLEEP, 30);
        Double weight = healthService.averageDays(com.lifedesk.health.HealthService.HealthField.WEIGHT, 30);
        Double exercise = healthService.averageDays(com.lifedesk.health.HealthService.HealthField.EXERCISE, 30);
        if (sleep == null && weight == null && exercise == null) {
            return "## Chưa có dữ liệu sức khỏe\n\n30 ngày qua chưa có bản ghi nào. "
                    + "Ghi giấc ngủ/vận động/cân nặng trong mục Sức khỏe để tôi tổng hợp.";
        }
        StringBuilder sb = new StringBuilder("## Trung bình 30 ngày gần nhất\n\n").append("| Chỉ số | Trung bình |\n")
                .append("|---|---:|\n");
        if (sleep != null) {
            sb.append("| Giấc ngủ | ").append(String.format("%.1f", sleep)).append(" giờ/đêm |\n");
        }
        if (exercise != null) {
            sb.append("| Vận động | ").append(Math.round(exercise)).append(" phút/ngày |\n");
        }
        if (weight != null) {
            sb.append("| Cân nặng | ").append(String.format("%.1f", weight)).append(" kg |\n");
        }
        sb.append("\n_Đây là số liệu bạn tự ghi nhận, không phải khuyến nghị y tế._");
        return sb.toString();
    }

    private String doSearch(String query) {
        List<SearchGroup> groups = searchService.query(query, null, 5);
        if (groups.isEmpty()) {
            return "Không tìm thấy kết quả nào cho \"" + query + "\".";
        }
        StringBuilder sb = new StringBuilder("## Kết quả cho \"" + query + "\"\n");
        int total = 0;
        for (SearchGroup g : groups) {
            sb.append("\n### ").append(g.displayType()).append('\n');
            for (var r : g.results()) {
                sb.append("- ").append(r.doc().title()).append('\n');
                total++;
            }
        }
        return sb.toString().stripTrailing() + "\n\n_" + total
                + " kết quả — mở tab Tìm kiếm để xem chi tiết._";
    }

    /** Extractive summarizer: honest about being rule-based (no LLM). */
    String summarizeNote(String titleQuery) {
        List<NoteMeta> matches = notesService.search(titleQuery);
        if (matches.isEmpty()) {
            return "Không tìm thấy ghi chú nào khớp \"" + titleQuery + "\".";
        }
        NoteMeta note = matches.get(0);
        String content = notesService.readContent(note.id);
        String plain = markdownService.toPlainText(content);

        List<String> headings = new ArrayList<>();
        for (String line : content.split("\n")) {
            if (line.stripLeading().startsWith("#")) {
                headings.add(line.replaceFirst("^#+\\s*", "").trim());
            }
        }
        List<String> sentences = splitSentences(plain);
        Map<String, Integer> freq = wordFrequency(plain);
        List<String> top = sentences.stream()
                .sorted(Comparator.comparingInt((String s) -> scoreSentence(s, freq)).reversed())
                .limit(4)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Tóm tắt RÚT TRÍCH (không dùng LLM) — \"").append(note.title).append("\":\n");
        sb.append("• Độ dài: ").append(plain.split("\\s+").length).append(" từ, ")
                .append(sentences.size()).append(" câu\n");
        if (!headings.isEmpty()) {
            sb.append("• Các đề mục: ").append(String.join(", ", headings.subList(0, Math.min(6, headings.size())))).append('\n');
        }
        for (String s : top) {
            sb.append("• ").append(s.length() > 160 ? s.substring(0, 160) + "…" : s).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("(?<=[.!?])\\s+|\\n+")) {
            String s = raw.trim();
            if (s.length() > 20) {
                out.add(s);
            }
        }
        return out;
    }

    private static Map<String, Integer> wordFrequency(String text) {
        Map<String, Integer> freq = new HashMap<>();
        for (String term : com.lifedesk.common.TextSearch.terms(text)) {
            if (term.length() > 3) {
                freq.merge(term, 1, Integer::sum);
            }
        }
        return freq;
    }

    private static int scoreSentence(String sentence, Map<String, Integer> freq) {
        int score = 0;
        for (String term : com.lifedesk.common.TextSearch.terms(sentence)) {
            score += freq.getOrDefault(term, 0);
        }
        return score;
    }

    /**
     * The one answer a personal assistant must get right: what deserves
     * attention today across every module, ranked, in one screen.
     * Every line comes from stored data — nothing here is inferred or invented.
     */
    private String briefing() {
        LocalDate today = LocalDate.now();
        List<Task> overdue = new ArrayList<>(taskService.overdue());
        List<Task> due = new ArrayList<>(taskService.dueToday());
        overdue.sort(java.util.Comparator.comparing((Task t) -> t.dueDate == null ? today : t.dueDate));
        due.sort(RuleBasedEngine::byUrgency);

        StringBuilder sb = new StringBuilder("## Tổng quan ").append(today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                .append(" — ").append(today.getDayOfWeek().getDisplayName(
                        java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("vi-VN")))
                .append("\n\n");

        if (overdue.isEmpty() && due.isEmpty()) {
            sb.append("**Không có việc nào quá hạn hay đến hạn hôm nay.** Ngày sạch việc.\n\n");
        } else {
            sb.append("### Ưu tiên ngay\n\n");
            int shown = 0;
            for (Task t : overdue) {
                if (shown++ >= 3) {
                    break;
                }
                long days = java.time.temporal.ChronoUnit.DAYS.between(t.dueDate == null ? today : t.dueDate, today);
                sb.append("- ").append(t.title).append(" — _quá hạn ").append(days).append(" ngày_")
                        .append(" · ").append(priorityLabel(t.priority)).append('\n');
            }
            for (Task t : due) {
                if (shown++ >= 5) {
                    break;
                }
                sb.append("- ").append(t.title).append(" — _hôm nay")
                        .append(t.dueTime != null ? ", " + t.dueTime : "")
                        .append(" · ").append(priorityLabel(t.priority)).append("_\n");
            }
            if (shown < overdue.size() + due.size()) {
                sb.append("- …và ").append(overdue.size() + due.size() - shown).append(" việc nữa\n");
            }
            sb.append('\n');
        }

        List<String> watch = new ArrayList<>();
        int cards = learningService.dueCount();
        if (cards > 0) {
            watch.add("**" + cards + " flashcard** đến hạn ôn — khoảng " + Math.max(5, cards * 2) + " phút.");
        }
        LocalDate yesterday = today.minusDays(1);
        List<String> missed = new ArrayList<>();
        for (Habit h : habitService.habits(false)) {
            if (h.scheduledOn(yesterday.getDayOfWeek()) && !habitService.isCompleted(h.id, yesterday)) {
                missed.add(h.name);
            }
        }
        if (!missed.isEmpty()) {
            watch.add("Hôm qua lỡ **" + String.join(", ", missed.subList(0, Math.min(3, missed.size())))
                    + "** — hôm nay làm lại là chuỗi vẫn tiếp.");
        }
        var month = financeService.monthSummary(java.time.YearMonth.now());
        if (month.expense().signum() > 0) {
            watch.add(month.net().signum() < 0
                    ? "Chi đang vượt thu **" + vnd(month.net().abs()) + "** trong tháng này."
                    : "Tháng này còn dư **" + vnd(month.net()) + "**.");
        }
        if (!watch.isEmpty()) {
            sb.append("### Cần chú ý\n\n");
            for (String w : watch) {
                sb.append("- ").append(w).append('\n');
            }
            sb.append('\n');
        }

        sb.append("### Bước tiếp theo\n\n");
        if (!overdue.isEmpty()) {
            sb.append("Xử lý \"").append(overdue.get(0).title).append("\" trước — nó đã trễ ")
                    .append(java.time.temporal.ChronoUnit.DAYS.between(
                            overdue.get(0).dueDate == null ? today : overdue.get(0).dueDate, today))
                    .append(" ngày. Hỏi tôi \"quá hạn\" để xem toàn bộ.");
        } else if (!due.isEmpty()) {
            sb.append("Bắt đầu với \"").append(due.get(0).title).append("\" (_")
                    .append(priorityLabel(due.get(0).priority)).append("_) — hỏi tôi \"việc hôm nay\" để xem cả danh sách.");
        } else if (cards > 0) {
            sb.append("Không có việc gấp — dồn 15 phút cho ").append(cards).append(" flashcard đang đến hạn.");
        } else {
            sb.append("Không có gì gấp. Dùng thời gian này để lên kế hoạch tuần: hỏi tôi \"sắp tới\".");
        }
        return sb.toString().stripTrailing();
    }

    private String suggestions() {
        List<String> out = new ArrayList<>();
        var overdue = taskService.overdue();
        if (!overdue.isEmpty()) {
            out.add("Xử lý " + overdue.size() + " công việc quá hạn (bắt đầu từ \"" + overdue.get(0).title + "\").");
        }
        var today = taskService.dueToday();
        if (!today.isEmpty()) {
            out.add("Hôm nay còn " + today.size() + " việc đến hạn — lên kế hoạch giờ làm cụ thể.");
        }
        var dueCards = learningService.dueCount();
        if (dueCards > 0) {
            out.add("Có " + dueCards + " flashcard đến hạn ôn tập (15 phút là đủ).");
        }
        for (Habit h : habitService.habits(false)) {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            if (h.scheduledOn(yesterday.getDayOfWeek()) && !habitService.isCompleted(h.id, yesterday)) {
                out.add("Hôm qua bạn lỡ thói quen \"" + h.name + "\" — hôm nay cố gắng giữ chuỗi nhé.");
            }
        }
        var month = financeService.monthSummary(java.time.YearMonth.now());
        if (month.expense().signum() > 0 && month.net().signum() < 0) {
            out.add("Tháng này chi đang vượt thu — xem lại các danh mục chi lớn.");
        }
        if (out.isEmpty()) {
            return "## Không có gì cần xử lý\n\nMọi thứ đang ổn: không việc quá hạn, không thẻ đến hạn, "
                    + "thói quen đều đặn. Đây là lúc tốt để nghĩ về việc dài hạn.";
        }
        StringBuilder sb = new StringBuilder("## Gợi ý cho bạn\n\n");
        int i = 1;
        for (String s : out) {
            sb.append(i++).append(". ").append(s).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Data snapshot used as chat context for LLM providers. */
    public String contextSnapshot() {
        LinkedHashMap<String, String> parts = new LinkedHashMap<>();
        parts.put("Công việc", "hôm nay: " + taskService.dueToday().size()
                + ", quá hạn: " + taskService.overdue().size()
                + ", tổng mở: " + taskService.openCount());
        var m = financeService.monthSummary(java.time.YearMonth.now());
        parts.put("Tài chính tháng này", "thu " + m.income().toPlainString() + " - chi " + m.expense().toPlainString());
        List<String> noteTitles = notesService.list().stream().limit(10).map(NoteMeta::title).toList();
        parts.put("Ghi chú gần đây", String.join(", ", noteTitles));
        StringBuilder habitLine = new StringBuilder();
        for (Habit h : habitService.habits(false)) {
            habitLine.append(h.name).append("(").append(habitService.currentStreak(h)).append(" ngày) ");
        }
        parts.put("Thói quen", habitLine.toString().trim());
        parts.put("Học tập", "flashcard đến hạn: " + learningService.dueCount());
        StringBuilder sb = new StringBuilder("Dữ liệu người dùng (đã cho phép truy cập):\n");
        parts.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }
}
