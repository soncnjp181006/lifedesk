package com.lifedesk.automation.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lifedesk.automation.Tool;
import com.lifedesk.automation.ToolContext;
import com.lifedesk.automation.ToolDescriptor;
import com.lifedesk.automation.ToolResult;
import com.lifedesk.notes.NoteMeta;
import com.lifedesk.notes.NotesService;
import com.lifedesk.notes.SaveResult;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Agent tools over the Notes module (create/search/delete). */
public final class NoteTools {

    private NoteTools() {
    }

    private static String str(JsonObject params, String key) {
        if (!params.has(key) || params.get(key).isJsonNull()) {
            return null;
        }
        String v = params.get(key).getAsString();
        return v == null || v.isBlank() ? null : v.trim();
    }

    public static Tool create(NotesService notesService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("note.create",
                        "Tạo ghi chú markdown mới. Params: title (bắt buộc), folder (tùy chọn), content, tags (mảng).",
                        com.lifedesk.automation.PermissionLevel.WRITE,
                        "{\"title\":\"string\",\"folder\":\"string?\",\"content\":\"string?\",\"tags\":[\"string?\"]}",
                        "{\"noteId\":\"string\",\"contentFile\":\"string\"}");
            }

            @Override
            public String validateParams(JsonObject params) {
                return str(params, "title") == null ? "Thiếu tham số bắt buộc: title" : null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                Set<String> tags = new LinkedHashSet<>();
                if (params.has("tags") && params.get("tags").isJsonArray()) {
                    for (JsonElement e : params.getAsJsonArray("tags")) {
                        tags.add(e.getAsString());
                    }
                }
                NoteMeta meta = notesService.create(str(params, "title"), str(params, "folder"),
                        params.has("content") && !params.get("content").isJsonNull()
                                ? params.get("content").getAsString() : "", tags);
                JsonObject data = new JsonObject();
                data.addProperty("noteId", meta.id);
                data.addProperty("contentFile", meta.contentFile);
                return ToolResult.ok("Đã tạo ghi chú \"" + meta.title + "\"", data);
            }
        };
    }

    public static Tool search(NotesService notesService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("note.search",
                        "Tìm ghi chú theo nội dung/tiêu đề/tag. Params: query, limit (mặc định 10).",
                        com.lifedesk.automation.PermissionLevel.READ,
                        "{\"query\":\"string?\",\"limit\":\"int?\"}",
                        "{\"notes\":[{\"id\":\"string\",\"title\":\"string\",\"folder\":\"string\"}]}");
            }

            @Override
            public String validateParams(JsonObject params) {
                return null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                String query = str(params, "query");
                int limit = params.has("limit") ? Math.min(50, params.get("limit").getAsInt()) : 10;
                List<NoteMeta> results = notesService.search(query);
                JsonArray arr = new JsonArray();
                int count = 0;
                for (NoteMeta n : results) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", n.id);
                    o.addProperty("title", n.title);
                    o.addProperty("folder", n.folder);
                    arr.add(o);
                    if (++count >= limit) {
                        break;
                    }
                }
                JsonObject data = new JsonObject();
                data.add("notes", arr);
                return ToolResult.ok(count + " ghi chú", data);
            }
        };
    }

    public static Tool delete(NotesService notesService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("note.delete",
                        "Xóa vĩnh viễn một ghi chú (YÊU CẦU XÁC NHẬN). Params: noteId (bắt buộc).",
                        com.lifedesk.automation.PermissionLevel.DESTRUCTIVE,
                        "{\"noteId\":\"string\"}",
                        "{\"deleted\":\"boolean\"}");
            }

            @Override
            public String validateParams(JsonObject params) {
                return str(params, "noteId") == null ? "Thiếu tham số bắt buộc: noteId" : null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                NoteMeta meta = notesService.get(str(params, "noteId"));
                notesService.delete(meta.id);
                JsonObject data = new JsonObject();
                data.addProperty("deleted", true);
                return ToolResult.ok("Đã xóa ghi chú \"" + meta.title + "\"", data);
            }
        };
    }

    public static Tool saveContent(NotesService notesService) {
        return new Tool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("note.append",
                        "Nối nội dung vào cuối ghi chú (ghi đè file => yêu cầu xác nhận). Params: noteId, content.",
                        com.lifedesk.automation.PermissionLevel.DESTRUCTIVE,
                        "{\"noteId\":\"string\",\"content\":\"string\"}",
                        "{\"noteId\":\"string\"}");
            }

            @Override
            public String validateParams(JsonObject params) {
                if (str(params, "noteId") == null) {
                    return "Thiếu tham số bắt buộc: noteId";
                }
                if (!params.has("content") || params.get("content").isJsonNull()
                        || params.get("content").getAsString().isBlank()) {
                    return "Thiếu tham số bắt buộc: content";
                }
                return null;
            }

            @Override
            public ToolResult execute(JsonObject params, ToolContext ctx) {
                String noteId = str(params, "noteId");
                String content = notesService.readContent(noteId);
                SaveResult result = notesService.saveContent(noteId,
                        content + "\n\n" + params.get("content").getAsString() + "\n", null);
                JsonObject data = new JsonObject();
                data.addProperty("noteId", noteId);
                data.addProperty("conflict", result == SaveResult.SAVED_WITH_CONFLICT_COPY);
                return ToolResult.ok("Đã nối nội dung vào ghi chú", data);
            }
        };
    }
}
