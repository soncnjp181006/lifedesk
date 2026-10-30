package com.lifedesk.agent;

import com.google.gson.JsonObject;

/**
 * One capability the Agent can operate. Tools are the safety boundary:
 * everything the agent does to the app goes through these explicit,
 * user-auditable operations.
 */
public interface AgentTool {
    /** Dotted name, e.g. "task.create" — also the LLM function name. */
    String name();

    /** One-line description (shown in the UI and given to the LLM). */
    String description();

    /** Human-readable argument hint, e.g. "title, priority?, due(YYYY-MM-DD)?". */
    String argsHint();

    /** Execute and return a human-readable result summary. Throws on invalid args. */
    String execute(JsonObject args) throws Exception;
}
