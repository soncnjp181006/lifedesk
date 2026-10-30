package com.lifedesk.agent;

import com.google.gson.JsonObject;
import com.lifedesk.app.AppContext;
import com.lifedesk.finance.PaymentMethod;
import com.lifedesk.finance.TxType;
import com.lifedesk.tasks.Priority;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The agent's tool registry over the app services. Adding a capability =
 * appending one tool to {@link #all} — the UI and the LLM tool prompt pick
 * it up automatically.
 */
public final class AgentTools {
    private final AppContext ctx;

    public AgentTools(AppContext ctx) {
        this.ctx = ctx;
    }

    public List<AgentTool> all() {
        List<AgentTool> tools = new ArrayList<>();
        tools.add(taskCreate());
        tools.add(taskList());
        tools.add(taskComplete());
        tools.add(financeSummary());
        tools.add(financeAdd());
        tools.add(financeBudgets());
        tools.add(habitList());
        tools.add(learningDue());
        tools.add(notesCreate());
        tools.add(notesAppend());
        tools.add(searchAll());
        return tools;
    }

    public AgentTool byName(String name) {
        for (AgentTool t : all()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        return null;
    }

    private AgentTool taskCreate() {
        return new AgentTool() {
            @Override public String name() { return "task.create"; }
            @Override public String description() { return "Tạo công việc mới"; }
            @Override public String argsHint() { return "title, priority?(LOW|MEDIUM|HIGH), due?(YYYY-MM-DD)"; }
            @Override public String execute(JsonObject args) {
                String title = args.get("title").getAsString();
                Priority p = args.has("priority") ? Priority.valueOf(args.get("priority").getAsString().toUpperCase()) : Priority.MEDIUM;
                LocalDate due = args.has("due") && !args.get("due").isJsonNull()
                        ? LocalDate.parse(args.get("due").getAsString()) : null;
                var t = ctx.tasks().create(title, "", p, due, null, "", null, null);
                return "Đã tạo công việc \"" + t.title + "\"" + (due != null ? " (hạn " + due + ")" : "");
            }
        };
    }

    private AgentTool taskList() {
        return new AgentTool() {
            @Override public String name() { return "task.list"; }
            @Override public String description() { return "Liệt kê công việc (hôm nay / quá hạn / tất cả)"; }
            @Override public String argsHint() { return "filter?(today|overdue|upcoming|all)"; }
            @Override public String execute(JsonObject args) {
                String filter = args.has("filter") ? args.get("filter").getAsString() : "all";
                List<?> tasks = switch (filter) {
                    case "today" -> ctx.tasks().dueToday();
                    case "overdue" -> ctx.tasks().overdue();
                    case "upcoming" -> ctx.tasks().upcoming(7);
                    default -> ctx.tasks().all();
                };
                if (tasks.isEmpty()) {
                    return "Không có công việc nào (" + filter + ").";
                }
                StringBuilder sb = new StringBuilder(tasks.size() + " công việc (" + filter + "):\n");
                for (Object o : tasks) {
                    if (o instanceof com.lifedesk.tasks.Task t) {
                        sb.append("• [").append(t.status).append("] ").append(t.title)
                                .append(t.dueDate != null ? " (hạn " + t.dueDate + ")" : "").append('\n');
                    }
                }
                return sb.toString().stripTrailing();
            }
        };
    }

    private AgentTool taskComplete() {
        return new AgentTool() {
            @Override public String name() { return "task.complete"; }
            @Override public String description() { return "Đánh dấu công việc hoàn thành theo id hoặc tiêu đề"; }
            @Override public String argsHint() { return "title"; }
            @Override public String execute(JsonObject args) {
                String title = args.get("title").getAsString();
                for (var t : ctx.tasks().all()) {
                    if (t.title.equalsIgnoreCase(title)) {
                        ctx.tasks().setStatus(t.id, com.lifedesk.tasks.TaskStatus.DONE);
                        return "Đã hoàn thành \"" + t.title + "\".";
                    }
                }
                throw new IllegalArgumentException("Không tìm thấy công việc \"" + title + "\"");
            }
        };
    }

    private AgentTool financeSummary() {
        return new AgentTool() {
            @Override public String name() { return "finance.summary"; }
            @Override public String description() { return "Tổng hợp thu chi tháng hiện tại"; }
            @Override public String argsHint() { return "(không có tham số)"; }
            @Override public String execute(JsonObject args) {
                var s = ctx.finance().monthSummary(java.time.YearMonth.now());
                return "Tháng " + java.time.YearMonth.now() + " — thu " + s.income().toPlainString()
                        + ", chi " + s.expense().toPlainString() + ", ròng " + s.net().toPlainString() + " VND";
            }
        };
    }

    private AgentTool financeAdd() {
        return new AgentTool() {
            @Override public String name() { return "finance.add"; }
            @Override public String description() { return "Ghi giao dịch thu/chi"; }
            @Override public String argsHint() { return "type(INCOME|EXPENSE), amount, categoryId, description?"; }
            @Override public String execute(JsonObject args) {
                TxType type = TxType.valueOf(args.get("type").getAsString().toUpperCase());
                BigDecimal amount = new BigDecimal(args.get("amount").getAsString());
                String categoryId = args.get("categoryId").getAsString();
                String desc = args.has("description") ? args.get("description").getAsString() : "";
                var t = ctx.finance().addTransaction(LocalDate.now(), type, amount, categoryId,
                        PaymentMethod.CASH, desc, null, null, false);
                return "Đã ghi giao dịch " + amount.toPlainString() + " VND (" + type + ").";
            }
        };
    }

    private AgentTool financeBudgets() {
        return new AgentTool() {
            @Override public String name() { return "finance.budgets"; }
            @Override public String description() { return "Trạng thái ngân sách tháng này"; }
            @Override public String argsHint() { return "(không có tham số)"; }
            @Override public String execute(JsonObject args) {
                var list = ctx.finance().budgetStatus(java.time.YearMonth.now());
                if (list.isEmpty()) {
                    return "Chưa đặt ngân sách nào.";
                }
                StringBuilder sb = new StringBuilder("Ngân sách tháng này:\n");
                for (var s : list) {
                    sb.append("• ").append(s.categoryName()).append(": đã chi ")
                            .append(s.spent().toPlainString()).append(" / hạn mức ")
                            .append(s.budget().limitAmount.toPlainString()).append('\n');
                }
                return sb.toString().stripTrailing();
            }
        };
    }

    private AgentTool habitList() {
        return new AgentTool() {
            @Override public String name() { return "habit.list"; }
            @Override public String description() { return "Danh sách thói quen + chuỗi hiện tại"; }
            @Override public String argsHint() { return "(không có tham số)"; }
            @Override public String execute(JsonObject args) {
                var habits = ctx.habits().habits(false);
                if (habits.isEmpty()) {
                    return "Chưa có thói quen nào.";
                }
                StringBuilder sb = new StringBuilder("Thói quen:\n");
                for (var h : habits) {
                    sb.append("• ").append(h.name).append(" — chuỗi ")
                            .append(ctx.habits().currentStreak(h)).append(" ngày\n");
                }
                return sb.toString().stripTrailing();
            }
        };
    }

    private AgentTool learningDue() {
        return new AgentTool() {
            @Override public String name() { return "learning.due"; }
            @Override public String description() { return "Số flashcard đến hạn ôn tập"; }
            @Override public String argsHint() { return "(không có tham số)"; }
            @Override public String execute(JsonObject args) {
                return "Flashcard đến hạn: " + ctx.learning().dueCount();
            }
        };
    }

    private AgentTool notesCreate() {
        return new AgentTool() {
            @Override public String name() { return "notes.create"; }
            @Override public String description() { return "Tạo ghi chú markdown mới"; }
            @Override public String argsHint() { return "title, content, folder?"; }
            @Override public String execute(JsonObject args) {
                String title = args.get("title").getAsString();
                String content = args.has("content") ? args.get("content").getAsString() : "";
                String folder = args.has("folder") ? args.get("folder").getAsString() : "";
                var note = ctx.notes().create(title, folder, content, null);
                return "Đã tạo ghi chú \"" + note.title + "\".";
            }
        };
    }

    private AgentTool notesAppend() {
        return new AgentTool() {
            @Override public String name() { return "notes.append"; }
            @Override public String description() { return "Nối nội dung vào ghi chú đang có (theo tiêu đề)"; }
            @Override public String argsHint() { return "title, content"; }
            @Override public String execute(JsonObject args) {
                String title = args.get("title").getAsString();
                String content = args.get("content").getAsString();
                for (var n : ctx.notes().list()) {
                    if (n.title.equalsIgnoreCase(title)) {
                        String existing = ctx.notes().readContent(n.id);
                        ctx.notes().saveContent(n.id, existing + "\n\n" + content, null);
                        return "Đã nối vào \"" + n.title + "\".";
                    }
                }
                throw new IllegalArgumentException("Không tìm thấy ghi chú \"" + title + "\"");
            }
        };
    }

    private AgentTool searchAll() {
        return new AgentTool() {
            @Override public String name() { return "search.all"; }
            @Override public String description() { return "Tìm kiếm xuyên module"; }
            @Override public String argsHint() { return "query"; }
            @Override public String execute(JsonObject args) {
                String query = args.get("query").getAsString();
                var groups = ctx.search().query(query, null, 3);
                if (groups.isEmpty()) {
                    return "Không có kết quả cho \"" + query + "\".";
                }
                StringBuilder sb = new StringBuilder("Kết quả cho \"" + query + "\":\n");
                for (var g : groups) {
                    sb.append("[").append(g.displayType()).append("]\n");
                    for (var r : g.results()) {
                        sb.append("• ").append(r.doc().title()).append('\n');
                    }
                }
                return sb.toString().stripTrailing();
            }
        };
    }
}
