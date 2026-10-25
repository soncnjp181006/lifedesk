package com.lifedesk.health;

import java.math.BigDecimal;

/** Definition of a custom health metric chosen by the user. */
public class MetricDef {
    public String id;
    public String name = "";
    public String unit = "";
    /** Optional personal target (NOT a medical recommendation). */
    public BigDecimal target;

    public MetricDef() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public MetricDef(String name, String unit, BigDecimal target) {
        this();
        this.name = name;
        this.unit = unit == null ? "" : unit;
        this.target = target;
    }
}
