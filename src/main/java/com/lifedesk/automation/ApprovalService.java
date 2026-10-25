package com.lifedesk.automation;

import java.util.function.BiFunction;

/**
 * Confirmation gate for sensitive agent actions (delete/overwrite/execute).
 * The UI registers a handler; with no handler (headless runs, scheduled jobs)
 * the request is DENIED by default — safe fallback.
 */
public class ApprovalService {
    public interface Handler {
        /** Show the action to the user; return true only on explicit approval. */
        boolean approve(AgentRun run, PlanStep step, String actionDescription);
    }

    private volatile Handler handler;

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public boolean requestApproval(AgentRun run, PlanStep step) {
        Handler h = handler;
        if (h == null) {
            return false;
        }
        String description = step.description == null || step.description.isBlank()
                ? step.tool : step.description;
        return h.approve(run, step, description);
    }
}
