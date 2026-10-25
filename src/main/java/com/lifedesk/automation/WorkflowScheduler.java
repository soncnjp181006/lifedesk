package com.lifedesk.automation;

import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.ValidationException;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Workflow persistence + scheduler. Due workflows are submitted to the agent
 * service as runs; auto-approve is honored only when every step is READ
 * (write/destructive steps always require human approval, scheduled or not).
 */
public class WorkflowScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowScheduler.class);
    private static final String FILE = "automation/workflows.json";

    private final JsonStore store;
    private final AgentService agentService;
    private final ChangeNotifier change = new ChangeNotifier();
    private final List<Workflow> workflows = new ArrayList<>();

    public WorkflowScheduler(JsonStore store, AgentService agentService) {
        this.store = store;
        this.agentService = agentService;
        load();
    }

    public ChangeNotifier changes() {
        return change;
    }

    private void load() {
        Type listType = new TypeToken<List<Workflow>>() {
        }.getType();
        JsonStore.StoredDoc doc = store.read(FILE, SchemaMigrator.none(1));
        if (doc == null || !doc.data().has("workflows")) {
            return;
        }
        List<Workflow> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray("workflows"), listType);
        if (parsed != null) {
            workflows.addAll(parsed);
        }
    }

    private void persist() {
        JsonObject data = new JsonObject();
        data.add("workflows", GsonFactory.create().toJsonTree(workflows));
        store.write(FILE, 1, data);
    }

    public List<Workflow> workflows() {
        return List.copyOf(workflows);
    }

    /** Save (insert or update). Enforces the auto-approve safety rule. */
    public synchronized Workflow save(Workflow workflow) {
        boolean destructive = false;
        for (PlanStep step : workflow.steps) {
            Tool tool = agentService.registry().find(step.tool).orElse(null);
            if (tool == null) {
                throw new ValidationException("Workflow dùng tool không tồn tại: " + step.tool);
            }
            if (tool.descriptor().permission() != PermissionLevel.READ) {
                destructive = true;
            }
        }
        if (destructive && workflow.autoApprove) {
            throw new ValidationException("Workflow chứa bước ghi/xóa — không thể bật tự động phê duyệt");
        }
        for (int i = 0; i < workflows.size(); i++) {
            if (workflows.get(i).id.equals(workflow.id)) {
                workflows.set(i, workflow);
                persist();
                change.fire();
                return workflow;
            }
        }
        workflows.add(workflow);
        persist();
        change.fire();
        return workflow;
    }

    public synchronized void delete(String id) {
        if (!workflows.removeIf(w -> w.id.equals(id))) {
            throw new com.lifedesk.common.NotFoundException("Không tìm thấy workflow: " + id);
        }
        persist();
        change.fire();
    }

    /** Register the periodic check on the app scheduler. */
    public void start(java.util.concurrent.ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(this::checkDue, 45, 30, java.util.concurrent.TimeUnit.SECONDS);
        LOG.info("Workflow scheduler started ({} workflows)", workflows.size());
    }

    /** One sweep; public so tests and smoke runs can invoke it directly. */
    public synchronized void checkDue() {
        LocalDateTime now = LocalDateTime.now();
        for (Workflow w : workflows) {
            if (!w.enabled || "NONE".equals(w.scheduleType)) {
                continue;
            }
            LocalDate lastDate = w.lastRunAt == null ? null : w.lastRunAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            boolean dueToday = switch (w.scheduleType) {
                case "DAILY" -> now.toLocalTime().isAfter(w.scheduleTime) || now.toLocalTime().equals(w.scheduleTime);
                case "WEEKLY" -> now.getDayOfWeek() == w.scheduleDay
                        && (now.toLocalTime().isAfter(w.scheduleTime) || now.toLocalTime().equals(w.scheduleTime));
                default -> false;
            };
            if (dueToday && (lastDate == null || lastDate.isBefore(now.toLocalDate()))) {
                LOG.info("Workflow due: {}", w.name);
                submit(w);
            }
        }
    }

    private void submit(Workflow w) {
        try {
            AgentPlan plan = new AgentPlan();
            plan.name = w.name;
            plan.origin = "workflow:" + w.id;
            plan.steps = new ArrayList<>(w.steps);
            boolean readOnly = w.steps.stream().allMatch(s ->
                    agentService.registry().find(s.tool)
                            .map(t -> t.descriptor().permission() == PermissionLevel.READ)
                            .orElse(false));
            w.lastRunAt = java.time.Instant.now();
            persist();
            agentService.submitRun(plan, w.autoApprove && readOnly, "workflow:" + w.id);
        } catch (RuntimeException e) {
            LOG.error("Workflow {} failed to submit: {}", w.name, e.toString());
        }
    }

    /** Manual trigger from the UI. */
    public AgentRun runNow(Workflow w) {
        AgentPlan plan = new AgentPlan();
        plan.name = w.name;
        plan.origin = "workflow:" + w.id + ":manual";
        plan.steps = new ArrayList<>(w.steps);
        w.lastRunAt = java.time.Instant.now();
        persist();
        return agentService.submitRun(plan, false, "manual");
    }
}
