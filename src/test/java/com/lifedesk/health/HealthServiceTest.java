package com.lifedesk.health;

import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.StorageIssueLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HealthServiceTest {

    @TempDir
    Path dir;

    private HealthService service() {
        return new HealthService(new JsonStore(dir, new StorageIssueLog()));
    }

    @Test
    void metricUpdateRenamesAndSetsTarget() {
        HealthService svc = service();
        MetricDef m = svc.addMetric("Huyết áp", "mmHg", null);
        svc.updateMetric(m.id, "Huyết áp tâm thu", "mmHg", new BigDecimal("120"));
        MetricDef updated = svc.metric(m.id);
        assertEquals("Huyết áp tâm thu", updated.name);
        assertEquals(new BigDecimal("120"), updated.target);
    }

    @Test
    void metricUpdateRejectsDuplicateName() {
        HealthService svc = service();
        MetricDef a = svc.addMetric("Nhịp tim", "bpm", null);
        svc.addMetric("Cân nhập tay", "kg", null);
        assertThrows(RuntimeException.class,
                () -> svc.updateMetric(a.id, "cân nhập tay", "kg", null));
    }

    @Test
    void metricDeleteRemovesValuesFromEntries() {
        HealthService svc = service();
        MetricDef m = svc.addMetric("Đường huyết", "mg/dL", null);
        svc.upsert(java.time.LocalDate.now(), null, null, null,
                java.util.Map.of(m.id, new BigDecimal("95")), "");
        svc.deleteMetric(m.id);
        assertTrue(svc.metrics().isEmpty());
        assertTrue(svc.byDate(java.time.LocalDate.now()).orElseThrow().customMetrics.isEmpty());
    }

    @Test
    void upsertValidatesRanges() {
        HealthService svc = service();
        assertThrows(RuntimeException.class, () -> svc.upsert(
                java.time.LocalDate.now(), new BigDecimal("30"), null, null, null, ""));
        assertThrows(RuntimeException.class, () -> svc.upsert(
                java.time.LocalDate.now(), null, 2000, null, null, ""));
        assertThrows(RuntimeException.class, () -> svc.upsert(
                java.time.LocalDate.now(), null, null, new BigDecimal("700"), null, ""));
    }
}
