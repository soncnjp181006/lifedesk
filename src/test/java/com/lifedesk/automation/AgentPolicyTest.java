package com.lifedesk.automation;

import com.google.gson.JsonObject;
import com.lifedesk.app.AppContext;
import com.lifedesk.config.AppConfig;
import com.lifedesk.security.SecurityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentPolicyTest {

    @TempDir
    Path dir;

    private AppContext context() {
        AppConfig config = new AppConfig();
        config.dataDir = dir.toString();
        config.remindersEnabled = false;
        return new AppContext(dir, config);
    }

    private static PlanStep step(String tool, JsonObject params) {
        PlanStep s = new PlanStep();
        s.tool = tool;
        s.params = params;
        return s;
    }

    private static JsonObject kv(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    @Test
    void unknownToolIsDenied_noShellExecution() {
        AppContext ctx = context();
        var plan = new AgentPlan();
        plan.steps = List.of(step("shell.exec", new JsonObject()));
        assertThrows(RuntimeException.class, () -> ctx.agentService().validatePlan(plan, true));
        assertFalse(ctx.agentService().registry().find("shell.exec").isPresent());
        assertFalse(ctx.agentService().registry().find("process.run").isPresent());
    }

    @Test
    void writeStepsRequirePlanApproval() {
        AppContext ctx = context();
        var plan = new AgentPlan();
        plan.steps = List.of(step("task.create", kv("title", "việc từ agent")));
        assertThrows(RuntimeException.class, () -> ctx.agentService().validatePlan(plan, false));
        ctx.agentService().validatePlan(plan, true); // approved passes
    }

    @Test
    void deniedToolBlocksWholePlan() {
        AppContext ctx = context();
        ctx.security().setDenied("task.create", true);
        var plan = new AgentPlan();
        plan.steps = List.of(step("task.create", kv("title", "x")));
        assertThrows(RuntimeException.class, () -> ctx.agentService().validatePlan(plan, true));
        ctx.security().setDenied("task.create", false);
        ctx.agentService().validatePlan(plan, true);
    }

    @Test
    void destructiveStepWithoutApprovalHandlerCancelsRun() throws Exception {
        AppContext ctx = context();
        var note = ctx.notes().create("Sẽ bị xóa", "", "nội dung", null);
        var plan = new AgentPlan();
        plan.steps = List.of(step("note.delete", kv("noteId", note.id)));
        AgentRun run = ctx.agentService().submitRun(plan, true, "manual");
        long deadline = System.currentTimeMillis() + 15000;
        while (!run.isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(RunStatus.CANCELLED, run.status);
        assertEquals("DENIED", run.steps.get(run.steps.size() - 1).status);
        // The note must still exist: denial must prevent deletion.
        assertTrue(ctx.notes().findByTitle("Sẽ bị xóa").isPresent());
    }

    @Test
    void destructiveStepWithExplicitApprovalExecutes() throws Exception {
        AppContext ctx = context();
        ctx.agentService().approvals().setHandler((run, step, desc) -> true);
        var note = ctx.notes().create("Chấp nhận xóa", "", "nd", null);
        var plan = new AgentPlan();
        plan.steps = List.of(step("note.delete", kv("noteId", note.id)));
        AgentRun run = ctx.agentService().submitRun(plan, true, "manual");
        long deadline = System.currentTimeMillis() + 15000;
        while (!run.isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(RunStatus.COMPLETED, run.status);
        assertTrue(ctx.notes().findByTitle("Chấp nhận xóa").isEmpty());
        ctx.agentService().approvals().setHandler(null);
    }

    @Test
    void stepLimitEnforcedAtValidation() {
        AppContext ctx = context();
        ctx.config().agent.maxSteps = 2;
        var plan = new AgentPlan();
        plan.steps = List.of(
                step("dashboard.summary", new JsonObject()),
                step("dashboard.summary", new JsonObject()),
                step("dashboard.summary", new JsonObject()));
        assertThrows(RuntimeException.class, () -> ctx.agentService().validatePlan(plan, true));
    }

    @Test
    void endToEndReadWriteRunCreatesTask() throws Exception {
        AppContext ctx = context();
        var plan = new AgentPlan();
        plan.steps = List.of(
                step("dashboard.summary", new JsonObject()),
                step("task.create", kv("title", "Agent E2E")));
        AgentRun run = ctx.agentService().submitRun(plan, true, "manual");
        long deadline = System.currentTimeMillis() + 15000;
        while (!run.isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(RunStatus.COMPLETED, run.status);
        assertEquals(1, ctx.tasks().search("Agent E2E", null, null).size());
        // every step recorded with attempts
        assertEquals(2, run.steps.size());
        assertTrue(run.steps.stream().allMatch(s -> "SUCCESS".equals(s.status)));
    }

    @Test
    void policyDecisionMatrix() {
        AppContext ctx = context();
        SecurityPolicy policy = ctx.security();
        var readTool = new ToolDescriptor("t.read", "", PermissionLevel.READ, "{}", "{}");
        var writeTool = new ToolDescriptor("t.write", "", PermissionLevel.WRITE, "{}", "{}");
        var destructive = new ToolDescriptor("t.destruct", "", PermissionLevel.DESTRUCTIVE, "{}", "{}");
        assertEquals(SecurityPolicy.Decision.ALLOWED, policy.checkPlanStep(readTool, false));
        assertEquals(SecurityPolicy.Decision.NEEDS_APPROVAL, policy.checkPlanStep(writeTool, false));
        assertEquals(SecurityPolicy.Decision.ALLOWED, policy.checkPlanStep(writeTool, true));
        assertEquals(SecurityPolicy.Decision.NEEDS_APPROVAL, policy.checkPlanStep(destructive, true));
        assertEquals(SecurityPolicy.Decision.DENIED, policy.checkPlanStep(null, true));
        policy.setDenied("t.read", true);
        assertEquals(SecurityPolicy.Decision.DENIED, policy.checkPlanStep(readTool, true));
    }

    @Test
    void auditParamsAreSanitized() {
        JsonObject params = new JsonObject();
        params.addProperty("api_key", "sk-secret-123");
        params.addProperty("Authorization", "Bearer abc");
        params.addProperty("title", "x".repeat(500));
        var clean = com.lifedesk.security.AuditLogger.sanitize(params);
        assertEquals("***", clean.get("api_key").getAsString());
        assertEquals("***", clean.get("Authorization").getAsString());
        assertTrue(clean.get("title").getAsString().length() < 260);
        assertTrue(clean.get("title").getAsString().endsWith("(truncated)"));
    }
}
