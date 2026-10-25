package com.lifedesk.security;

import com.lifedesk.automation.PermissionLevel;
import com.lifedesk.automation.ToolDescriptor;
import com.google.gson.JsonObject;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Security policy for the automation agent.
 *
 * Decisions:
 * - unknown tools are always denied;
 * - READ tools run when not explicitly denied;
 * - WRITE tools require the plan itself to be approved before the run starts;
 * - DESTRUCTIVE tools always require a live confirmation at step execution
 *   time (see ApprovalService) unless the user disabled the tool entirely;
 * - per-tool overrides may only set DENY (the agent can never elevate its
 *   own permissions, and no tool can modify this policy).
 */
public class SecurityPolicy {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityPolicy.class);
    private static final String FILE = "security/policy.json";

    public enum Decision { ALLOWED, NEEDS_APPROVAL, DENIED }

    private final JsonStore store;
    /** toolName -> "DENIED" (only deny overrides exist by design). */
    private final Map<String, String> toolOverrides = new HashMap<>();

    public SecurityPolicy(JsonStore store) {
        this.store = store;
        load();
    }

    private void load() {
        JsonStore.StoredDoc doc = store.read(FILE, SchemaMigrator.none(1));
        if (doc == null) {
            return;
        }
        toolOverrides.clear();
        if (doc.data().has("toolOverrides") && doc.data().get("toolOverrides").isJsonObject()) {
            for (var e : doc.data().getAsJsonObject("toolOverrides").entrySet()) {
                toolOverrides.put(e.getKey(), e.getValue().getAsString());
            }
        }
    }

    private void persist() {
        JsonObject data = new JsonObject();
        JsonObject overrides = new JsonObject();
        toolOverrides.forEach(overrides::addProperty);
        data.add("toolOverrides", overrides);
        store.write(FILE, 1, data);
    }

    /** User-managed deny list. The agent has no path to this method. */
    public void setDenied(String toolName, boolean denied) {
        if (denied) {
            toolOverrides.put(toolName, "DENIED");
        } else {
            toolOverrides.remove(toolName);
        }
        persist();
        LOG.info("Policy override: {} {}", toolName, denied ? "DENIED" : "cleared");
    }

    public boolean isDenied(String toolName) {
        return "DENIED".equalsIgnoreCase(toolOverrides.get(toolName));
    }

    public Map<String, String> overrides() {
        return Map.copyOf(toolOverrides);
    }

    /** Plan-level check before a run may start. */
    public Decision checkPlanStep(ToolDescriptor tool, boolean planApproved) {
        if (tool == null) {
            return Decision.DENIED; // unknown tool
        }
        if (isDenied(tool.name())) {
            return Decision.DENIED;
        }
        return switch (tool.permission()) {
            case READ -> Decision.ALLOWED;
            case WRITE -> planApproved ? Decision.ALLOWED : Decision.NEEDS_APPROVAL;
            case DESTRUCTIVE -> Decision.NEEDS_APPROVAL; // always confirmed again at execution time
        };
    }

    /** Path confinement for file tools: the resolved root must be in the granted list. */
    public void checkPath(Path candidate, List<Path> allowedRoots) {
        com.lifedesk.security.PathGuard.requireInside(allowedRoots, candidate);
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("Tool bị chặn (DENIED): " + (toolOverrides.isEmpty() ? "(không có)" : String.join(", ", toolOverrides.keySet())));
        return lines;
    }
}
