package com.lifedesk.automation;

import java.time.Instant;

/** Record of one executed step (audit trail). Params stored here are sanitized. */
public class StepRecord {
    public String stepId;
    public String tool;
    public String paramsSanitized = "";
    /** "SUCCESS" | "FAILED" | "SKIPPED" | "AWAITING_APPROVAL" | "DENIED" | "TIMEOUT" */
    public String status;
    public String message = "";
    public int attempts = 0;
    public Instant startedAt;
    public Instant finishedAt;

    public StepRecord() {
    }

    public StepRecord(String stepId, String tool) {
        this.stepId = stepId;
        this.tool = tool;
    }
}
