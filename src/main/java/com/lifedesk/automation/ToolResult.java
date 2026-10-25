package com.lifedesk.automation;

import com.google.gson.JsonObject;

/** Outcome of one tool execution. */
public record ToolResult(boolean success, String message, JsonObject data) {
    public static ToolResult ok(String message, JsonObject data) {
        return new ToolResult(true, message, data == null ? new JsonObject() : data);
    }

    public static ToolResult fail(String message) {
        return new ToolResult(false, message, new JsonObject());
    }

    public boolean retryable() {
        return data != null && data.has("retryable") && data.get("retryable").getAsBoolean();
    }
}
