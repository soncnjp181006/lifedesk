package com.lifedesk.automation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** One agent run over one plan. */
public class AgentRun {
    public String id;
    public String planId;
    public String planName = "";
    public String planJson = "";
    public RunStatus status = RunStatus.PENDING;
    public String statusNote = "";
    public List<StepRecord> steps = new ArrayList<>();
    public Instant createdAt = Instant.now();
    public Instant startedAt;
    public Instant finishedAt;
    /** Trigger: "manual" | "workflow:<id>". */
    public String trigger = "manual";
    public transient AtomicBoolean cancelRequested = new AtomicBoolean(false);
    /** Set while the executor waits on a destructive-step confirmation. */
    public transient volatile String awaitingStepId;

    public AgentRun() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public AgentRun(AgentPlan plan) {
        this();
        this.planId = plan.id;
        this.planName = plan.name;
        this.planJson = com.lifedesk.storage.GsonFactory.create().toJson(plan);
    }

    public boolean isTerminal() {
        return status == RunStatus.COMPLETED || status == RunStatus.FAILED || status == RunStatus.CANCELLED;
    }
}
