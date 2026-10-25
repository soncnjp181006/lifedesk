package com.lifedesk.automation.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lifedesk.automation.Tool;
import com.lifedesk.automation.ToolContext;
import com.lifedesk.automation.ToolDescriptor;
import com.lifedesk.automation.ToolResult;
import com.lifedesk.files.FileHit;
import com.lifedesk.files.FileManagerService;
import com.lifedesk.files.FileSearchOptions;
import com.lifedesk.security.PathGuard;
import com.lifedesk.tasks.TaskService;

import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Agent tools: file search (read-only, confined to user-granted roots) and dashboard aggregation. */
public final class InfoTools {

    private InfoTools() {
    }

    private static String str(JsonObject params, String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) {
            return null;
        }
        String v = params.get(key).getAsString();
        return v == null || v.isBlank() ? null : v.trim();
    }

    public static Tool fileSearch(FileManagerService fileService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("file.search",
                        "Tìm file theo tên/phần mở rộng CHỈ trong thư mục được cấp quyền. "
                                + "Params: root (bắt buộc, phải nằm trong allowed roots), nameQuery, extensions (mảng), maxResults.",
                        com.lifedesk.automation.PermissionLevel.READ,
                        "{\"root\":\"string\",\"nameQuery\":\"string?\",\"extensions\":[\"string?\"],\"maxResults\":\"int?\"}",
                        "{\"hits\":[{\"path\":\"string\",\"name\":\"string\",\"sizeBytes\":\"long\"}]}");
            }

            @Override
            public String validateParams(JsonObject params) {
                String root = str(params, "root");
                if (root == null) {
                    return "Thiếu tham số bắt buộc: root";
                }
                if (!fileService.isAllowed(Paths.get(root))) {
                    return "Thư mục root không nằm trong danh sách được cấp quyền";
                }
                return null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                FileSearchOptions options = new FileSearchOptions();
                options.root = PathGuard.requireInside(fileService.allowedRoots(), Paths.get(str(params, "root")));
                options.nameQuery = str(params, "nameQuery") == null ? "" : str(params, "nameQuery");
                if (params.has("extensions") && params.get("extensions").isJsonArray()) {
                    options.extensions = params.getAsJsonArray("extensions").asList().stream()
                            .map(e -> e.getAsString().toLowerCase(Locale.ROOT))
                            .collect(java.util.stream.Collectors.toSet());
                }
                if (params.has("maxResults")) {
                    options.maxResults = Math.min(200, params.get("maxResults").getAsInt());
                }
                List<FileHit> hits = fileService.search(options);
                JsonArray arr = new JsonArray();
                int count = 0;
                for (FileHit hit : hits) {
                    JsonObject o = new JsonObject();
                    o.addProperty("path", hit.path().toString());
                    o.addProperty("name", hit.name());
                    o.addProperty("sizeBytes", hit.sizeBytes());
                    arr.add(o);
                    if (++count >= options.maxResults) {
                        break;
                    }
                }
                JsonObject data = new JsonObject();
                data.add("hits", arr);
                return ToolResult.ok(count + " file khớp (trong phạm vi được cấp quyền)", data);
            }
        };
    }

    public record DashboardSources(TaskService tasks, com.lifedesk.finance.FinanceService finance,
                                   com.lifedesk.habits.HabitService habits, com.lifedesk.learning.LearningService learning) {
    }

    public static Tool dashboardSummary(DashboardSources sources) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("dashboard.summary",
                        "Tổng hợp số liệu dashboard: công việc hôm nay/quá hạn, thu chi tháng, thói quen, flashcard đến hạn. Không cần params.",
                        com.lifedesk.automation.PermissionLevel.READ,
                        "{}",
                        "{\"tasksDueToday\":\"int\",\"tasksOverdue\":\"int\",\"income\":\"string\",\"expense\":\"string\","
                                + "\"habits\":\"int\",\"flashcardsDue\":\"int\"}");
            }

            @Override
            public String validateParams(JsonObject params) {
                return null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                var month = sources.finance().monthSummary(java.time.YearMonth.now());
                JsonObject data = new JsonObject();
                data.addProperty("tasksDueToday", sources.tasks().dueToday().size());
                data.addProperty("tasksOverdue", sources.tasks().overdue().size());
                data.addProperty("income", month.income().toPlainString());
                data.addProperty("expense", month.expense().toPlainString());
                data.addProperty("habits", sources.habits().habits(false).size());
                data.addProperty("flashcardsDue", sources.learning().dueCount());
                return ToolResult.ok("Đã tổng hợp dashboard", data);
            }
        };
    }

    public static Tool habitLog(com.lifedesk.habits.HabitService habitService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("habit.log",
                        "Ghi nhận hoàn thành thói quen. Params: habitId (bắt buộc), completed (mặc định true), "
                                + "date (yyyy-MM-dd, chỉ cho phép hôm nay).",
                        com.lifedesk.automation.PermissionLevel.WRITE,
                        "{\"habitId\":\"string\",\"completed\":\"boolean?\",\"date\":\"yyyy-MM-dd?\"}",
                        "{\"habitId\":\"string\",\"date\":\"string\",\"completed\":\"boolean\"}");
            }

            @Override
            public String validateParams(JsonObject params) {
                return str(params, "habitId") == null ? "Thiếu tham số bắt buộc: habitId" : null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                String habitId = str(params, "habitId");
                habitService.habit(habitId); // existence check
                boolean completed = optBool(params, "completed", true);
                java.time.LocalDate date = java.time.LocalDate.now();
                if (params.has("date") && !params.get("date").isJsonNull()) {
                    date = java.time.LocalDate.parse(params.get("date").getAsString());
                    if (!date.isEqual(java.time.LocalDate.now())) {
                        return ToolResult.fail("Agent chỉ được ghi nhận thói quen cho ngày hôm nay");
                    }
                }
                habitService.setCompletion(habitId, date, completed, "Ghi nhận bởi agent run " + ctx.runId(),
                        "agent:" + ctx.runId());
                JsonObject data = new JsonObject();
                data.addProperty("habitId", habitId);
                data.addProperty("date", date.toString());
                data.addProperty("completed", completed);
                return ToolResult.ok("Đã ghi nhận thói quen", data);
            }
        };
    }

    /** Deep-set boolean helper (params.completed default true). */
    private static boolean optBool(JsonObject params, String key, boolean dflt) {
        if (!params.has(key) || params.get(key).isJsonNull()) {
            return dflt;
        }
        try {
            return params.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    public static Set<String> noExtensions() {
        return Set.of();
    }
}
