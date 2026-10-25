package com.lifedesk.automation;

import com.google.gson.JsonObject;

/** One step of an agent plan. */
public class PlanStep {
    public String id;
    public String tool = "";
    public String description = "";
    public JsonObject params = new JsonObject();
    /** Continue with the next step even when this step fails. */
    public boolean continueOnFailure = false;

    public PlanStep() {
        this.id = com.lifedesk.common.Ids.shortId();
    }
}
