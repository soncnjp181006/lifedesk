package com.lifedesk.automation;

import com.google.gson.JsonObject;
import com.lifedesk.common.Fingerprint;
import com.lifedesk.common.ValidationException;
import com.lifedesk.config.AppConfig;
import com.lifedesk.security.AuditLogger;
import com.lifedesk.security.SecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes agent plans with hard safety rails:
 *
 * 1. plan validation against the tool registry and security policy;
 * 2. WRITE steps require plan approval before start, DESTRUCTIVE steps require
 *    a live confirmation at execution time;
 * 3. anti-infinite-loop guards: max steps, wall-clock deadline, per-step
 *    timeout, repeated-(tool,params) detection;
 * 4. bounded retries for retryable failures;
 * 5. cancellation is honored between steps and via thread interruption;
 * 6. every step is appended to the audit log (sanitized params);
 * 7. NO shell execution tool exists — unknown tools are denied by policy.
 */
public class AgentExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(AgentExecutor.class);
    private static final int MAX_IDENTICAL_STEPS = 3;

    private final ToolRegistry registry;
    private final SecurityPolicy policy;
    private final ApprovalService approvals;
    private final AuditLogger audit;
    private final AppConfig.AgentLimits limits;
    private final Runnable onChange;
    /** Dedicated pool so a hung tool can be interrupted without blocking the runner pool. */
    private final ExecutorService stepPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lifedesk-agent-step");
        t.setDaemon(true);
        return t;
    });

    public AgentExecutor(ToolRegistry registry, SecurityPolicy policy, ApprovalService approvals,
                         AuditLogger audit, AppConfig.AgentLimits limits, Runnable onChange) {
        this.registry = registry;
        this.policy = policy;
        this.approvals = approvals;
        this.audit = audit;
        this.limits = limits;
        this.onChange = onChange;
    }

    /** Validate the plan before execution; throws ValidationException with a clear message. */
    public void validate(AgentPlan plan, boolean planApproved) {
        if (plan.steps.isEmpty()) {
            throw new ValidationException("Kế hoạch không có bước nào");
        }
        if (plan.steps.size() > Math.max(1, limits.maxSteps)) {
            throw new ValidationException("Kế hoạch vượt quá số bước cho phép (" + limits.maxSteps + ")");
        }
        for (PlanStep step : plan.steps) {
            Tool tool = registry.find(step.tool).orElse(null);
            SecurityPolicy.Decision decision = policy.checkPlanStep(tool == null ? null : tool.descriptor(), planApproved);
            if (decision == SecurityPolicy.Decision.DENIED) {
                throw new ValidationException("Bước \"" + step.tool + "\" bị từ chối bởi chính sách bảo mật");
            }
            if (decision == SecurityPolicy.Decision.NEEDS_APPROVAL && !planApproved) {
                throw new ValidationException("Kế hoạch có bước ghi/dữ liệu nhạy cảm — cần được phê duyệt trước khi chạy");
            }
            String invalid = tool.validateParams(step.params);
            if (invalid != null) {
                throw new ValidationException("Bước \"" + step.tool + "\" tham số không hợp lệ: " + invalid);
            }
        }
    }

    /** Run the plan synchronously (call from a background thread). */
    public void execute(AgentRun run, AgentPlan plan) {
        run.status = RunStatus.RUNNING;
        run.startedAt = Instant.now();
        long deadline = System.currentTimeMillis() + Math.max(10, limits.maxRunSeconds) * 1000L;
        Map<String, Integer> loopGuard = new HashMap<>();
        boolean anyBlockingFailure = false;
        onChange.run();

        for (int i = 0; i < plan.steps.size(); i++) {
            PlanStep step = plan.steps.get(i);

            if (run.cancelRequested.get()) {
                finishCancelled(run, i);
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                run.statusNote = "Vượt quá thời lượng cho phép (" + limits.maxRunSeconds + "s)";
                markRemainingSkipped(run, plan, i, "run timeout");
                run.status = RunStatus.FAILED;
                run.finishedAt = Instant.now();
                onChange.run();
                return;
            }

            StepRecord record = new StepRecord(step.id, step.tool);
            record.startedAt = Instant.now();
            run.steps.add(record);
            onChange.run();

            Tool tool = registry.find(step.tool).orElse(null);
            if (tool == null) {
                record.status = "FAILED";
                record.message = "Tool không tồn tại trong registry";
                finishStep(run, record, step, null);
                anyBlockingFailure = true;
                if (!step.continueOnFailure) {
                    markRemainingSkipped(run, plan, i + 1, "step trước đó thất bại");
                    break;
                }
                continue;
            }

            // Anti-infinite-loop: identical (tool, params) repeated too often.
            String loopKey = step.tool + ":" + Fingerprint.sha256(step.params.toString()).substring(0, 12);
            int repeats = loopGuard.merge(loopKey, 1, Integer::sum);
            if (repeats > MAX_IDENTICAL_STEPS) {
                record.status = "FAILED";
                record.message = "Phát hiện vòng lặp: cùng tool+tham số lặp lại quá " + MAX_IDENTICAL_STEPS + " lần";
                finishStep(run, record, step, null);
                anyBlockingFailure = true;
                if (!step.continueOnFailure) {
                    markRemainingSkipped(run, plan, i + 1, "phát hiện vòng lặp");
                    break;
                }
                continue;
            }

            // Destructive actions require live confirmation.
            if (tool.descriptor().permission() == PermissionLevel.DESTRUCTIVE) {
                run.status = RunStatus.WAITING_APPROVAL;
                run.awaitingStepId = step.id;
                record.status = "AWAITING_APPROVAL";
                onChange.run();
                boolean approved = approvals.requestApproval(run, step);
                run.awaitingStepId = null;
                run.status = RunStatus.RUNNING;
                if (!approved) {
                    record.status = "DENIED";
                    record.message = "Người dùng không phê duyệt hành động nhạy cảm";
                    record.finishedAt = Instant.now();
                    finishStep(run, record, step, null);
                    run.status = RunStatus.CANCELLED;
                    run.statusNote = "Bị hủy do từ chối phê duyệt bước nhạy cảm";
                    markRemainingSkipped(run, plan, i + 1, "từ chối phê duyệt");
                    run.finishedAt = Instant.now();
                    onChange.run();
                    return;
                }
                onChange.run();
            }

            // Execute with bounded retries and per-step timeout.
            int maxAttempts = 1 + Math.max(0, limits.maxRetries);
            boolean success = false;
            String lastError = "";
            for (int attempt = 1; attempt <= maxAttempts && !run.cancelRequested.get(); attempt++) {
                record.attempts = attempt;
                Future<ToolResult> future = stepPool.submit(() -> tool.execute(step.params,
                        new ToolContext(run.id, "agent:" + run.id)));
                try {
                    ToolResult result = future.get(Math.max(1, limits.perStepSeconds), TimeUnit.SECONDS);
                    if (result.success()) {
                        success = true;
                        record.status = "SUCCESS";
                        record.message = result.message();
                        break;
                    }
                    lastError = result.message();
                    if (!result.retryable()) {
                        break;
                    }
                } catch (TimeoutException e) {
                    future.cancel(true);
                    lastError = "Timeout sau " + limits.perStepSeconds + "s";
                    LOG.warn("Agent step {} timed out", step.tool);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    future.cancel(true);
                    lastError = "Bị gián đoạn";
                    break;
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            }

            record.finishedAt = Instant.now();
            if (success) {
                record.status = "SUCCESS";
            } else {
                record.status = "FAILED";
                record.message = lastError;
            }
            finishStep(run, record, step, tool);

            if (!success && !step.continueOnFailure) {
                anyBlockingFailure = true;
                markRemainingSkipped(run, plan, i + 1, "bước trước thất bại");
                break;
            }
        }

        run.finishedAt = Instant.now();
        run.status = anyBlockingFailure ? RunStatus.FAILED : RunStatus.COMPLETED;
        if (run.cancelRequested.get() && !anyBlockingFailure) {
            run.status = RunStatus.CANCELLED;
        }
        onChange.run();
    }

    private void finishStep(AgentRun run, StepRecord record, PlanStep step, Tool tool) {
        long durationMs = record.startedAt == null ? 0
                : record.finishedAt == null ? 0 : record.finishedAt.toEpochMilli() - record.startedAt.toEpochMilli();
        audit.log(run.id, record.stepId, record.tool, step.params, record.status, record.message, durationMs);
        onChange.run();
    }

    private void markRemainingSkipped(AgentRun run, AgentPlan plan, int fromIndex, String reason) {
        for (int i = fromIndex; i < plan.steps.size(); i++) {
            StepRecord skipped = new StepRecord(plan.steps.get(i).id, plan.steps.get(i).tool);
            skipped.status = "SKIPPED";
            skipped.message = "Bỏ qua: " + reason;
            run.steps.add(skipped);
            audit.log(run.id, skipped.stepId, skipped.tool, new JsonObject(), "SKIPPED", reason, 0);
        }
        onChange.run();
    }

    private void finishCancelled(AgentRun run, int fromIndex) {
        run.statusNote = "Bị hủy bởi người dùng";
        run.finishedAt = Instant.now();
        run.status = RunStatus.CANCELLED;
        onChange.run();
    }

    public void shutdown() {
        stepPool.shutdownNow();
    }
}
