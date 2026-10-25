package com.lifedesk.health;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** One manual health log entry per day. */
public class HealthEntry {
    public String id;
    public LocalDate date;
    public BigDecimal sleepHours;
    /** Minutes of exercise/movement. */
    public Integer exerciseMinutes;
    public BigDecimal weightKg;
    /** Custom metric values keyed by MetricDef id. */
    public Map<String, BigDecimal> customMetrics = new HashMap<>();
    public String note = "";

    public HealthEntry() {
        this.id = com.lifedesk.common.Ids.newId();
    }
}
