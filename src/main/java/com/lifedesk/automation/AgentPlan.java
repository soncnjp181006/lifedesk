package com.lifedesk.automation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A plan the agent will execute: an ordered list of tool steps. */
public class AgentPlan {
    public String id;
    public String name = "";
    public List<PlanStep> steps = new ArrayList<>();
    public Instant createdAt = Instant.now();
    /** Who produced the plan: "user" or "ai:<provider>". */
    public String origin = "user";

    public AgentPlan() {
        this.id = com.lifedesk.common.Ids.newId();
    }
}
