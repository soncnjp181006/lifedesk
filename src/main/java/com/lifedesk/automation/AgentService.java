package com.lifedesk.automation;

import com.google.gson.JsonObject;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.ValidationException;
import com.lifedesk.config.AppConfig;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import com.lifedesk.security.AuditLogger;
import com.lifedesk.security.SecurityPolicy;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Agent facade: plan submission, run lifecycle, run history persistence
 * (automation/runs.json, capped) and change notification for the UI.
 */
public class AgentService {
    private static final Logger LOG = LoggerFactory.getLogger(AgentService.class);
    private static final String RUNS_FILE = "automation/runs.json";
    private static final int MAX_PERSISTED_RUNS = 50;

    private final ToolRegistry registry;
    private final SecurityPolicy policy;
    private final ApprovalService approvals = new ApprovalService();
    private final AgentExecutor executor;
    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();
    private final List<AgentRun> runs = new ArrayList<>();
    private final ExecutorService runnerPool;

    public AgentService(ToolRegistry registry, SecurityPolicy policy, AuditLogger audit,
                        AppConfig.AgentLimits limits, JsonStore store, ExecutorService runnerPool) {
        this.registry = registry;
        this.policy = policy;
        this.store = store;
        this.runnerPool = runnerPool;
        this.executor = new AgentExecutor(registry, policy, approvals, audit, limits, this::fire);
        loadRuns();
    }

    public ChangeNotifier changes() {
        return change;
    }

    private void fire() {
        persistRuns();
        change.fire();
    }

    public ToolRegistry registry() {
        return registry;
    }

    public SecurityPolicy policy() {
        return policy;
    }

    public ApprovalService approvals() {
        return approvals;
    }

    /** Validate + enqueue a run. Returns the PENDING run immediately. */
    public AgentRun submitRun(AgentPlan plan, boolean planApproved, String trigger) {
        executor.validate(plan, planApproved);
        AgentRun run = new AgentRun(plan);
        run.trigger = trigger;
        synchronized (runs) {
            runs.add(0, run);
        }
        fire();
        runnerPool.execute(() -> {
            try {
                executor.execute(run, plan);
            } catch (RuntimeException e) {
                LOG.error("Agent run crashed", e);
                run.status = RunStatus.FAILED;
                run.statusNote = "Lỗi runtime: " + e.getMessage();
                run.finishedAt = java.time.Instant.now();
                fire();
            } finally {
                persistRuns();
            }
        });
        return run;
    }

    public void cancel(String runId) {
        findRun(runId).ifPresent(run -> {
            if (!run.isTerminal()) {
                run.cancelRequested.set(true);
                if (run.status == RunStatus.PENDING || run.status == RunStatus.WAITING_APPROVAL) {
                    run.status = RunStatus.CANCELLED;
                    run.statusNote = "Hủy theo yêu cầu người dùng";
                    run.finishedAt = java.time.Instant.now();
                    fire();
                }
            }
        });
    }

    public List<AgentRun> runs() {
        synchronized (runs) {
            return List.copyOf(runs);
        }
    }

    public java.util.Optional<AgentRun> findRun(String id) {
        synchronized (runs) {
            return runs.stream().filter(r -> r.id.equals(id)).findFirst();
        }
    }

    private void loadRuns() {
        Type listType = new TypeToken<List<AgentRun>>() {
        }.getType();
        JsonStore.StoredDoc doc = store.read(RUNS_FILE, SchemaMigrator.none(1));
        if (doc == null || !doc.data().has("runs")) {
            return;
        }
        List<AgentRun> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray("runs"), listType);
        if (parsed != null) {
            for (AgentRun r : parsed) {
                r.cancelRequested = new java.util.concurrent.atomic.AtomicBoolean(false);
                // A run persisted mid-execution can never resume; mark it failed.
                if (!r.isTerminal()) {
                    r.status = RunStatus.FAILED;
                    r.statusNote = "Ứng dụng thoát giữa lúc chạy — đánh dấu FAILED khi khôi phục";
                }
                runs.add(r);
            }
        }
    }

    private void persistRuns() {
        List<AgentRun> snapshot;
        synchronized (runs) {
            if (runs.size() > MAX_PERSISTED_RUNS) {
                runs.subList(MAX_PERSISTED_RUNS, runs.size()).clear();
            }
            snapshot = new ArrayList<>(runs);
        }
        JsonObject data = new JsonObject();
        data.add("runs", GsonFactory.create().toJsonTree(snapshot));
        try {
            store.write(RUNS_FILE, 1, data);
        } catch (RuntimeException e) {
            LOG.warn("Persist runs failed: {}", e.toString());
        }
    }

    /** Tests + smoke: force-persist now. */
    public void flush() {
        persistRuns();
    }

    /** Validation exposed for UI plan preview. */
    public void validatePlan(AgentPlan plan, boolean approved) {
        executor.validate(plan, approved);
    }

    /** Stop the step pool (app exit). */
    public void shutdown() {
        executor.shutdown();
    }
}
