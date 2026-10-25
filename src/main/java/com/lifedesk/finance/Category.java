package com.lifedesk.finance;

/** Expense/income category. */
public class Category {
    public String id;
    public String name = "";
    public TxType type = TxType.EXPENSE;
    public boolean archived = false;

    public Category() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public Category(String name, TxType type) {
        this();
        this.name = name;
        this.type = type;
    }
}
