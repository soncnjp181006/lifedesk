package com.lifedesk.finance;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Receivable (ai nợ tôi) or payable (tôi nợ ai) tracker. */
public class Debt {
    public String id;
    public DebtDirection direction = DebtDirection.RECEIVABLE;
    public String party = "";
    public BigDecimal amount = BigDecimal.ZERO;
    public LocalDate incurredDate;
    public LocalDate dueDate;
    public DebtStatus status = DebtStatus.OPEN;
    public String note = "";
    public java.time.Instant createdAt = java.time.Instant.now();
    public java.time.Instant settledAt;

    public Debt() {
        this.id = com.lifedesk.common.Ids.newId();
    }
}
