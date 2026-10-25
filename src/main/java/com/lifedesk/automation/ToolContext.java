package com.lifedesk.automation;

/** Execution context passed to tools: which run invoked them and on whose behalf. */
public record ToolContext(String runId, String actor) {
}
