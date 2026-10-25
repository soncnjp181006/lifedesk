package com.lifedesk.automation;

/**
 * Machine-readable contract of a tool: input/output schema (simplified JSON
 * schema descriptions) plus its permission level. The agent can only act
 * through registered tools — never arbitrary OS access.
 */
public record ToolDescriptor(String name, String description, PermissionLevel permission,
                             String inputSchema, String outputSchema) {
}
