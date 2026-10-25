package com.lifedesk.finance;

import java.math.BigDecimal;
import java.time.YearMonth;

/** Monthly spending limit per category. */
public class Budget {
    public String id;
    public YearMonth month;
    public String categoryId = "";
    public BigDecimal limitAmount = BigDecimal.ZERO;

    public Budget() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public Budget(YearMonth month, String categoryId, BigDecimal limitAmount) {
        this();
        this.month = month;
        this.categoryId = categoryId;
        this.limitAmount = limitAmount;
    }
}
