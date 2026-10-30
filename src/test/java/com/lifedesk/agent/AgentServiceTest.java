package com.lifedesk.agent;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentServiceTest {

    @Test
    void parsesToolPlanFromReply() {
        JsonArray plan = AgentService.parseToolPlan(
                "Đây là kế hoạch:\n```json\n[{\"tool\":\"task.create\",\"args\":{\"title\":\"Báo cáo\"}}]\n```");
        assertNotNull(plan);
        assertEquals(1, plan.size());
        assertEquals("task.create", plan.get(0).getAsJsonObject().get("tool").getAsString());
    }

    @Test
    void plainAnswerIsNotAPlan() {
        assertNull(AgentService.parseToolPlan("Bạn nên lập kế hoạch bằng cách chia nhỏ công việc."));
    }

    @Test
    void arrayWithoutToolKeyIsNotAPlan() {
        assertNull(AgentService.parseToolPlan("Các việc: [một, hai, ba]"));
    }

    @Test
    void brokenJsonIsNotAPlan() {
        assertNull(AgentService.parseToolPlan("[{\"tool\": "));
    }
}
