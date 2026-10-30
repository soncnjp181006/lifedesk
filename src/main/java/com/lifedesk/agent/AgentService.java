package com.lifedesk.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lifedesk.app.AppContext;
import com.lifedesk.ai.ChatMessage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Agent orchestration: built-in multi-step workflows (deterministic tool
 * sequences that produce a report note) and a natural-language command loop
 * that lets an LLM pick tools from {@link AgentTools}.
 */
public class AgentService {
    /** Live progress for one workflow/command step. */
    public record StepUpdate(int index, String label, String result, boolean ok) {
    }

    /** One deterministic step of a workflow. */
    public record WorkflowStep(String label, Supplier<String> action) {
    }

    public record Workflow(String id, String name, String description, List<WorkflowStep> steps) {
    }

    private final AppContext ctx;
    private final AgentTools tools;

    public AgentService(AppContext ctx) {
        this.ctx = ctx;
        this.tools = new AgentTools(ctx);
    }

    public AgentTools tools() {
        return tools;
    }

    // ------------------------------------------------------------------ workflows

    /** The built-in workflow catalog ("Agent Workflow" cards in the UI). */
    public List<Workflow> workflows() {
        List<Workflow> out = new ArrayList<>();
        out.add(new Workflow("morning-review", "Soạn kế hoạch buổi sáng",
                "Quét việc quá hạn/hôm nay, thói quen, flashcard → tạo ghi chú kế hoạch ngày.",
                List.of(
                        new WorkflowStep("Việc quá hạn", () -> run("task.list", "{\"filter\":\"overdue\"}")),
                        new WorkflowStep("Việc hôm nay", () -> run("task.list", "{\"filter\":\"today\"}")),
                        new WorkflowStep("Thói quen", () -> run("habit.list", "{}")),
                        new WorkflowStep("Flashcard đến hạn", () -> run("learning.due", "{}")),
                        new WorkflowStep("Tạo ghi chú kế hoạch", this::createMorningPlanNote))));
        out.add(new Workflow("finance-check", "Rà soát tài chính tuần",
                "Tổng hợp thu chi + ngân sách → ghi chú báo cáo.",
                List.of(
                        new WorkflowStep("Tổng hợp thu chi", () -> run("finance.summary", "{}")),
                        new WorkflowStep("Ngân sách", () -> run("finance.budgets", "{}")),
                        new WorkflowStep("Tạo ghi chú báo cáo", this::createFinanceNote))));
        out.add(new Workflow("study-session", "Chuẩn bị phiên học",
                "Flashcard đến hạn + kế hoạch học → ghi chú phiên học.",
                List.of(
                        new WorkflowStep("Flashcard đến hạn", () -> run("learning.due", "{}")),
                        new WorkflowStep("Việc liên quan học tập", () -> run("task.list", "{\"filter\":\"today\"}")),
                        new WorkflowStep("Tạo ghi chú phiên học", this::createStudyNote))));
        return out;
    }

    /** Run a workflow on {@code executor}; every step reports through {@code onStep}. */
    public void runWorkflowAsync(Workflow workflow, java.util.concurrent.Executor executor,
                                 Consumer<StepUpdate> onStep, Runnable onDone) {
        executor.execute(() -> {
            int index = 1;
            for (WorkflowStep step : workflow.steps()) {
                try {
                    String result = step.action().get();
                    onStep.accept(new StepUpdate(index, step.label(), result, true));
                } catch (Exception e) {
                    onStep.accept(new StepUpdate(index, step.label(),
                            e.getMessage() == null ? e.toString() : e.getMessage(), false));
                }
                index++;
            }
            onDone.run();
        });
    }

    private String run(String toolName, String argsJson) {
        AgentTool tool = tools.byName(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Không có tool: " + toolName);
        }
        try {
            return tool.execute(JsonParser.parseString(argsJson).getAsJsonObject());
        } catch (Exception e) {
            throw new RuntimeException(toolName + ": " + e.getMessage(), e);
        }
    }

    private String today() {
        return LocalDate.now().format(DateTimeFormatter.ISO_DATE);
    }

    private String createMorningPlanNote() {
        String title = "Kế hoạch " + today();
        String content = "# Kế hoạch " + today() + "\n\n"
                + "## Việc hôm nay\n" + run("task.list", "{\"filter\":\"today\"}") + "\n\n"
                + "## Quá hạn\n" + run("task.list", "{\"filter\":\"overdue\"}") + "\n\n"
                + "## Thói quen\n" + run("habit.list", "{}") + "\n\n"
                + "## Ôn tập\n" + run("learning.due", "{}") + "\n";
        var existing = ctx.notes().findByTitle(title);
        if (existing.isPresent()) {
            ctx.notes().saveContent(existing.get().id, content, null);
            return "Đã cập nhật ghi chú \"" + title + "\".";
        }
        ctx.notes().create(title, "kế hoạch", content, null);
        return "Đã tạo ghi chú \"" + title + "\".";
    }

    private String createFinanceNote() {
        String title = "Báo cáo tài chính " + today();
        String content = "# Tài chính " + today() + "\n\n"
                + run("finance.summary", "{}") + "\n\n"
                + run("finance.budgets", "{}") + "\n";
        var existing = ctx.notes().findByTitle(title);
        if (existing.isPresent()) {
            ctx.notes().saveContent(existing.get().id, content, null);
            return "Đã cập nhật \"" + title + "\".";
        }
        ctx.notes().create(title, "tài chính", content, null);
        return "Đã tạo ghi chú \"" + title + "\".";
    }

    private String createStudyNote() {
        String title = "Phiên học " + today();
        String content = "# Phiên học " + today() + "\n\n"
                + "## Flashcard đến hạn\n" + run("learning.due", "{}") + "\n\n"
                + "## Việc trong ngày\n" + run("task.list", "{\"filter\":\"today\"}") + "\n";
        var existing = ctx.notes().findByTitle(title);
        if (existing.isPresent()) {
            ctx.notes().saveContent(existing.get().id, content, null);
            return "Đã cập nhật \"" + title + "\".";
        }
        ctx.notes().create(title, "học tập", content, null);
        return "Đã tạo ghi chú \"" + title + "\".";
    }

    // ------------------------------------------------------------------ LLM command loop

    /**
     * Natural-language command: with an LLM active the model picks tools from
     * the registry (max 3 rounds); with the rule engine the instruction goes
     * straight to {@code handle()}. Emits progress through {@code onStep}.
     */
    public void runCommandAsync(String instruction, java.util.concurrent.Executor executor,
                                Consumer<StepUpdate> onStep, Consumer<String> onAnswer,
                                Consumer<Throwable> onError) {
        executor.execute(() -> {
            try {
                var provider = ctx.ai().currentProvider().orElse(null);
                if (provider == null) {
                    onStep.accept(new StepUpdate(1, "AI tắt", "Bật AI trong Cài đặt để Agent hoạt động.", false));
                    onError.accept(new IllegalStateException("AI đang tắt"));
                    return;
                }
                if (com.lifedesk.ai.RuleBasedEngine.ID.equals(provider.id())) {
                    onStep.accept(new StepUpdate(1, "Engine quy tắc", "Xử lý lệnh cục bộ...", true));
                    onAnswer.accept(((com.lifedesk.ai.RuleBasedEngine) provider).handle(instruction));
                    return;
                }
                llmToolLoop(provider, instruction, onStep, onAnswer);
            } catch (Throwable t) {
                onError.accept(t);
            }
        });
    }

    private void llmToolLoop(com.lifedesk.ai.AiProvider provider, String instruction,
                             Consumer<StepUpdate> onStep, Consumer<String> onAnswer) {
        String toolSpec = buildToolSpec();
        StringBuilder transcript = new StringBuilder();
        String lastReply = "";
        for (int round = 1; round <= 3; round++) {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system("""
                    Bạn là agent điều khiển ứng dụng LifeDesk. Các tool khả dụng:
                    %s
                    Trả lời DUY NHẤT bằng một trong hai dạng:
                    1. Mảng JSON các bước cần chạy: [{"tool":"<name>","args":{...}}] — không giải thích thêm.
                    2. Câu trả lời cuối cho người dùng (markdown, tiếng Việt) khi đã đủ thông tin hoặc không cần tool.
                    Kết quả các tool đã chạy ở các vòng trước:
                    %s""".formatted(toolSpec, transcript.isEmpty() ? "(chưa có)" : transcript.toString())));
            messages.add(ChatMessage.user(instruction));
            String reply = provider.chat(messages, ctx.ai().activeModel());
            lastReply = reply;
            JsonArray plan = parseToolPlan(reply);
            if (plan == null || plan.isEmpty()) {
                onAnswer.accept(reply);
                return;
            }
            for (var el : plan) {
                JsonObject call = el.getAsJsonObject();
                String name = call.has("tool") ? call.get("tool").getAsString() : "?";
                JsonObject args = call.has("args") && call.get("args").isJsonObject()
                        ? call.getAsJsonObject("args") : new JsonObject();
                try {
                    AgentTool tool = tools.byName(name);
                    if (tool == null) {
                        throw new IllegalArgumentException("tool không tồn tại");
                    }
                    String result = tool.execute(args);
                    onStep.accept(new StepUpdate(round, name + " " + args, result, true));
                    transcript.append(name).append('(').append(args).append(") → ").append(result).append('\n');
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    onStep.accept(new StepUpdate(round, name + " " + args, msg, false));
                    transcript.append(name).append('(').append(args).append(") → LỖI: ").append(msg).append('\n');
                }
            }
        }
        onAnswer.accept(lastReply.isBlank()
                ? "Agent đã chạy các bước, xem log bên dưới." : lastReply);
    }

    private String buildToolSpec() {
        StringBuilder sb = new StringBuilder();
        for (AgentTool t : tools.all()) {
            sb.append("- ").append(t.name()).append(" — ").append(t.description())
                    .append(" | args: ").append(t.argsHint()).append('\n');
        }
        return sb.toString();
    }

    /** Extract a JSON tool-plan array from a reply; null when the reply is a final answer. */
    static JsonArray parseToolPlan(String reply) {
        int from = reply.indexOf('[');
        int to = reply.lastIndexOf(']');
        if (from < 0 || to <= from) {
            return null;
        }
        try {
            JsonArray arr = JsonParser.parseString(reply.substring(from, to + 1)).getAsJsonArray();
            if (arr.size() > 0 && arr.get(0).isJsonObject() && arr.get(0).getAsJsonObject().has("tool")) {
                return arr;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
