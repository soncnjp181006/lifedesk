package com.lifedesk.automation;

import com.google.gson.JsonObject;

/** A single callable agent tool. Tools validate their own inputs; the executor enforces policy. */
public interface Tool {
    ToolDescriptor descriptor();

    /** Validate params against the tool contract; return an error message or null when valid. */
    String validateParams(JsonObject params);

    ToolResult execute(JsonObject params, ToolContext ctx) throws Exception;
}
