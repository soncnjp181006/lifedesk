package com.lifedesk.automation.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lifedesk.automation.Tool;
import com.lifedesk.automation.ToolContext;
import com.lifedesk.automation.ToolDescriptor;
import com.lifedesk.automation.ToolResult;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.Task;
import com.lifedesk.tasks.TaskService;
import com.lifedesk.tasks.TaskStatus;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** Agent tools over the Tasks module. */
public final class TaskTools {

    private TaskTools() {
    }

    private static String requireString(JsonObject params, String key) {
        if (!params.has(key) || params.get(key).isJsonNull() || params.get(key).getAsString().isBlank()) {
            return null;
        }
        return params.get(key).getAsString().trim();
    }

    public static Tool create(TaskService taskService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("task.create",
                        "Tạo công việc mới. Params: title (bắt buộc), dueDate (yyyy-MM-dd, tùy chọn), "
                                + "priority (LOW|MEDIUM|HIGH|URGENT), notes, group, tags (mảng).",
                        com.lifedesk.automation.PermissionLevel.WRITE,
                        "{\"title\":\"string\",\"dueDate\":\"yyyy-MM-dd?\",\"priority\":\"enum?\",\"tags\":[\"string?\"]}",
                        "{\"taskId\":\"string\",\"title\":\"string\"}");
            }

            @Override
            public String validateParams(JsonObject params) {
                if (requireString(params, "title") == null) {
                    return "Thiếu tham số bắt buộc: title";
                }
                String due = requireString(params, "dueDate");
                if (due != null) {
                    try {
                        LocalDate.parse(due);
                    } catch (DateTimeParseException e) {
                        return "dueDate không hợp lệ (cần yyyy-MM-dd): " + due;
                    }
                }
                String pr = requireString(params, "priority");
                if (pr != null) {
                    try {
                        Priority.valueOf(pr.toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        return "priority không hợp lệ: " + pr;
                    }
                }
                return null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                LocalDate due = null;
                String dueStr = requireString(params, "dueDate");
                if (dueStr != null) {
                    due = LocalDate.parse(dueStr);
                }
                Priority priority = Priority.MEDIUM;
                String pr = requireString(params, "priority");
                if (pr != null) {
                    priority = Priority.valueOf(pr.toUpperCase(Locale.ROOT));
                }
                java.util.List<String> tags = new java.util.ArrayList<>();
                if (params.has("tags") && params.get("tags").isJsonArray()) {
                    for (JsonElement e : params.getAsJsonArray("tags")) {
                        tags.add(e.getAsString());
                    }
                }
                Task t = taskService.create(requireString(params, "title"),
                        requireString(params, "notes"), priority, due, null,
                        requireString(params, "group"), tags, null);
                JsonObject data = new JsonObject();
                data.addProperty("taskId", t.id);
                data.addProperty("title", t.title);
                return ToolResult.ok("Đã tạo công việc \"" + t.title + "\"", data);
            }
        };
    }

    public static Tool list(TaskService taskService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("task.list",
                        "Liệt kê công việc. Params: status (TODO|IN_PROGRESS|DONE|CANCELLED), group, limit (mặc định 20).",
                        com.lifedesk.automation.PermissionLevel.READ,
                        "{\"status\":\"enum?\",\"group\":\"string?\",\"limit\":\"int?\"}",
                        "{\"tasks\":[{\"id\":\"string\",\"title\":\"string\",\"status\":\"enum\",\"dueDate\":\"date?\"}]}");
            }

            @Override
            public String validateParams(JsonObject params) {
                return null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                String statusFilter = requireString(params, "status");
                String group = requireString(params, "group");
                int limit = params.has("limit") ? Math.min(100, params.get("limit").getAsInt()) : 20;
                JsonArray arr = new JsonArray();
                int count = 0;
                for (Task t : taskService.all()) {
                    if (statusFilter != null && !t.status.name().equalsIgnoreCase(statusFilter)) {
                        continue;
                    }
                    if (group != null && !group.equalsIgnoreCase(t.group)) {
                        continue;
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("id", t.id);
                    o.addProperty("title", t.title);
                    o.addProperty("status", t.status.name());
                    o.addProperty("priority", t.priority.name());
                    if (t.dueDate != null) {
                        o.addProperty("dueDate", t.dueDate.toString());
                    }
                    arr.add(o);
                    if (++count >= limit) {
                        break;
                    }
                }
                JsonObject data = new JsonObject();
                data.add("tasks", arr);
                return ToolResult.ok(count + " công việc", data);
            }
        };
    }

    public static Tool updateStatus(TaskService taskService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("task.update_status",
                        "Đổi trạng thái công việc. Params: taskId (bắt buộc), status (TODO|IN_PROGRESS|DONE|CANCELLED).",
                        com.lifedesk.automation.PermissionLevel.WRITE,
                        "{\"taskId\":\"string\",\"status\":\"enum\"}",
                        "{\"taskId\":\"string\",\"status\":\"enum\"}");
            }

            @Override
            public String validateParams(JsonObject params) {
                if (requireString(params, "taskId") == null) {
                    return "Thiếu tham số bắt buộc: taskId";
                }
                String status = requireString(params, "status");
                if (status == null) {
                    return "Thiếu tham số bắt buộc: status";
                }
                try {
                    TaskStatus.valueOf(status.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    return "status không hợp lệ: " + status;
                }
                return null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                String taskId = requireString(params, "taskId");
                TaskStatus status = TaskStatus.valueOf(requireString(params, "status").toUpperCase(Locale.ROOT));
                taskService.setStatus(taskId, status);
                JsonObject data = new JsonObject();
                data.addProperty("taskId", taskId);
                data.addProperty("status", status.name());
                return ToolResult.ok("Đã chuyển trạng thái sang " + status, data);
            }
        };
    }

    public static Tool search(TaskService taskService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("task.search",
                        "Tìm công việc theo từ khóa (tiêu đề/ghi chú/tag). Params: query, limit (mặc định 10).",
                        com.lifedesk.automation.PermissionLevel.READ,
                        "{\"query\":\"string?\",\"limit\":\"int?\"}",
                        "{\"tasks\":[{\"id\":\"string\",\"title\":\"string\"}]}");
            }

            @Override
            public String validateParams(JsonObject params) {
                return null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                String query = requireString(params, "query");
                int limit = params.has("limit") ? Math.min(50, params.get("limit").getAsInt()) : 10;
                List<Task> results = taskService.search(query, null, null);
                JsonArray arr = new JsonArray();
                int count = 0;
                for (Task t : results) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", t.id);
                    o.addProperty("title", t.title);
                    o.addProperty("status", t.status.name());
                    arr.add(o);
                    if (++count >= limit) {
                        break;
                    }
                }
                JsonObject data = new JsonObject();
                data.add("tasks", arr);
                return ToolResult.ok(count + " kết quả", data);
            }
        };
    }
}
