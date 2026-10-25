package com.lifedesk.tasks;

/** One checklist line inside a task. */
public class ChecklistItem {
    public String text = "";
    public boolean done = false;

    public ChecklistItem() {
    }

    public ChecklistItem(String text, boolean done) {
        this.text = text;
        this.done = done;
    }
}
