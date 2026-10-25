package com.lifedesk.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/** A single income/expense transaction. Money is always {@link BigDecimal}. */
public class Transaction {
    public String id;
    public LocalDate date;
    public TxType type = TxType.EXPENSE;
    public BigDecimal amount = BigDecimal.ZERO;
    public String currency = "VND";
    public String categoryId = "";
    public PaymentMethod method = PaymentMethod.CASH;
    public String description = "";
    public Set<String> tags = new LinkedHashSet<>();
    /** Optional external reference used for import de-duplication. */
    public String externalId = "";
    public String importFingerprint = "";
    public java.time.Instant createdAt = java.time.Instant.now();

    public Transaction() {
        this.id = com.lifedesk.common.Ids.newId();
    }
}
