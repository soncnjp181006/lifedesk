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

    /** Dispatch one user utterance. Public so the smoke test can exercise it. */
    public String handle(String input) {
        if (input == null || input.isBlank()) {
            return help();
        }
        String q = input.trim();

        Matcher m = CREATE_TASK.matcher(q);
        if (m.find()) {
            return createTask(m.group(1));
        }
        m = SUMMARIZE_NOTE.matcher(q);
        if (m.find()) {
            return summarizeNote(m.group(1));
        }
        m = SEARCH_CMD.matcher(q);
        if (m.find() && !q.toLowerCase(Locale.ROOT).contains("tìm kiếm model")) {
            return doSearch(m.group(1));
        }
        String lower = com.lifedesk.common.TextSearch.normalize(q);
        if (lower.contains("tro giup") || lower.contains("help") || lower.contains("lam duoc gi")
                || lower.equals("?") || lower.contains("huong dan")) {
            return help();
        }
        if (lower.contains("hom nay") || lower.contains("today") || lower.contains("viec hom nay")) {
            return tasksDueToday();
        }
        if (lower.contains("qua han") || lower.contains("overdue")) {
            return tasksOverdue();
        }
        if (lower.contains("sap toi") || lower.contains("upcoming") || lower.contains("lich sap toi")) {
            return upcoming();
        }
        if (lower.contains("chi tieu") || lower.contains("thu chi") || lower.contains("tai chinh")
                || lower.contains("finance") || lower.contains("expense")) {
            return financeSummary();
        }
        if (lower.contains("thoi quen") || lower.contains("habit") || lower.contains("streak")) {
            return habitStatus();
        }
        if (lower.contains("muc tieu") || lower.contains("goal")) {
            return goalStatus();
        }
        if (lower.contains("on tap") || lower.contains("flashcard") || lower.contains("hoc tap")
                || lower.contains("review") || lower.contains("learning")) {
            return learningStatus();
        }
        if (lower.contains("goi y") || lower.contains("suggest") || lower.contains("nen lam gi")) {
            return suggestions();
        }
        if (lower.contains("suc khoe") || lower.contains("health") || lower.contains("can nang")) {
            return healthStatus();
        }
        if (lower.contains("tom tat") || lower.contains("summary")) {
            return "Để tóm tắt, hãy dùng lệnh: \"tóm tắt ghi chú <tên ghi chú>\".";
        }
        return unknown(q);
    }

    private String help() {
        return """
                Tôi là engine quy tắc chạy hoàn toàn offline (không phải LLM). Các lệnh tôi hiểu:
                • "việc hôm nay" / "today" — danh sách công việc đến hạn hôm nay
                • "quá hạn" / "overdue" — công việc trễ hạn
                • "sắp tới" — lịch 7 ngày tới
                • "tạo task: <tiêu đề>" — tạo công việc mới (hỗ trợ "hạn mai", "hạn 2026-01-31", "hạn 31/12")
                • "tóm tắt ghi chú: <tên>" — tóm tắt rút trích từ nội dung ghi chú
                • "tìm: <từ khóa>" — tìm kiếm xuyên module
                • "chi tiêu tháng này" — tổng hợp thu chi
                • "thói quen" — chuỗi ngày và tỷ lệ hoàn thành
                • "mục tiêu" — tiến độ mục tiêu
                • "ôn tập" — số flashcard đến hạn
                • "sức khỏe" — trung bình giấc ngủ/vận động/cân nặng
                • "gợi ý" — đề xuất việc cần làm
                Muốn hỏi đáp ngôn ngữ tự nhiên? Bật provider Ollama hoặc Kilo trong Cài đặt → AI.""";
    }

    private String unknown(String q) {
        return "Câu hỏi \"" + q + "\" nằm ngoài khả năng của engine quy tắc (tôi không đoán hay suy luận ngôn ngữ tự nhiên).\n"
                + "Gõ \"help\" để xem các lệnh hỗ trợ, hoặc bật Ollama/Kilo trong Cài đặt → AI để dùng LLM.";
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

    private String tasksDueToday() {
        List<Task> list = new ArrayList<>(taskService.dueToday());
        if (list.isEmpty()) {
            return "Hôm nay không có công việc nào đến hạn. 🎉";
        }
        StringBuilder sb = new StringBuilder("Công việc đến hạn hôm nay (" + list.size() + "):\n");
        for (Task t : list) {
            sb.append("• [").append(t.priority).append("] ").append(t.title);
            if (t.dueTime != null) {
                sb.append(" lúc ").append(t.dueTime);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String tasksOverdue() {
        List<Task> list = new ArrayList<>(taskService.overdue());
        if (list.isEmpty()) {
            return "Không có công việc nào quá hạn. 👍";
        }
        StringBuilder sb = new StringBuilder("Công việc quá hạn (" + list.size() + "):\n");
        for (Task t : list) {
            sb.append("• ").append(t.title).append(" (hạn ").append(t.dueDate).append(")\n");
        }
        return sb.toString();
    }

    private String upcoming() {
        List<Task> list = new ArrayList<>(taskService.upcoming(7));
        if (list.isEmpty()) {
            return "Không có lịch nào trong 7 ngày tới.";
        }
        StringBuilder sb = new StringBuilder("Lịch 7 ngày tới:\n");
        for (Task t : list) {
            sb.append("• ").append(t.dueDate).append(": ").append(t.title).append('\n');
        }
        return sb.toString();
    }

    private String financeSummary() {
        var now = java.time.YearMonth.now();
        var s = financeService.monthSummary(now);
        return "Tổng hợp " + now + "\n"
                + "• Thu nhập: " + s.income().toPlainString() + " VND\n"
                + "• Chi tiêu: " + s.expense().toPlainString() + " VND\n"
                + "• Ròng: " + s.net().toPlainString() + " VND";
    }

    private String habitStatus() {
        List<Habit> habits = habitService.habits(false);
        if (habits.isEmpty()) {
            return "Chưa có thói quen nào.";
        }
        StringBuilder sb = new StringBuilder("Thói quen hiện tại:\n");
        for (Habit h : habits) {
            sb.append("• ").append(h.name)
                    .append(" — chuỗi hiện tại: ").append(habitService.currentStreak(h))
                    .append(" ngày, tỷ lệ 30 ngày: ")
                    .append(Math.round(habitService.completionRate(h, 30) * 100)).append("%\n");
        }
        return sb.toString();
    }

    private String goalStatus() {
        var goals = habitService.goals();
        var active = goals.stream().filter(g -> g.status() == com.lifedesk.habits.GoalStatus.ACTIVE).toList();
        if (active.isEmpty()) {
            return "Không có mục tiêu đang hoạt động.";
        }
        StringBuilder sb = new StringBuilder("Mục tiêu đang hoạt động:\n");
        for (var g : active) {
            sb.append("• ").append(g.title()).append(" — tiến độ ").append(g.progressMode() == com.lifedesk.habits.ProgressMode.MANUAL
                    ? g.manualProgress() + "%" : "tự động từ task liên kết").append('\n');
        }
        return sb.toString();
    }

    private String learningStatus() {
        int due = learningService.dueCount();
        var courses = learningService.courses().stream()
                .filter(c -> c.status() == com.lifedesk.learning.CourseStatus.IN_PROGRESS).toList();
        StringBuilder sb = new StringBuilder();
        sb.append("• Flashcard đến hạn ôn tập: ").append(due).append('\n');
        sb.append("• Khóa học đang học: ").append(courses.size());
        for (var c : courses) {
            sb.append("\n  - ").append(c.title()).append(" (").append(c.progressPercent()).append("%)");
        }
        return sb.toString();
    }

    private String healthStatus() {
        Double sleep = healthService.averageDays(com.lifedesk.health.HealthService.HealthField.SLEEP, 30);
        Double weight = healthService.averageDays(com.lifedesk.health.HealthService.HealthField.WEIGHT, 30);
        Double exercise = healthService.averageDays(com.lifedesk.health.HealthService.HealthField.EXERCISE, 30);
        if (sleep == null && weight == null && exercise == null) {
            return "Chưa có dữ liệu sức khỏe nào trong 30 ngày qua.";
        }
        StringBuilder sb = new StringBuilder("Trung bình 30 ngày gần nhất:\n");
        if (sleep != null) {
            sb.append("• Giấc ngủ: ").append(sleep).append(" giờ/đêm\n");
        }
        if (exercise != null) {
            sb.append("• Vận động: ").append(Math.round(exercise)).append(" phút/ngày\n");
        }
        if (weight != null) {
            sb.append("• Cân nặng: ").append(weight).append(" kg\n");
        }
        sb.append("(Chỉ là số liệu bạn ghi nhận, không phải khuyến nghị y tế.)");
        return sb.toString();
    }

    private String doSearch(String query) {
        List<SearchGroup> groups = searchService.query(query, null, 5);
        if (groups.isEmpty()) {
            return "Không tìm thấy kết quả nào cho \"" + query + "\".";
        }
        StringBuilder sb = new StringBuilder("Kết quả cho \"" + query + "\":\n");
        int total = 0;
        for (SearchGroup g : groups) {
            sb.append("\n[").append(g.displayType()).append("]\n");
            for (var r : g.results()) {
                sb.append("• ").append(r.doc().title()).append('\n');
                total++;
            }
        }
        return sb.toString().stripTrailing() + "\n(" + total + " kết quả — mở tab Tìm kiếm để xem chi tiết)";
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
            return "Hiện không có gợi ý nào — mọi thứ đang ổn! 🎉";
        }
        StringBuilder sb = new StringBuilder("Gợi ý cho bạn:\n");
        for (String s : out) {
            sb.append("• ").append(s).append('\n');
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
