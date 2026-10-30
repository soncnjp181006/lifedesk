package com.lifedesk.ui.components;

import javafx.scene.control.CheckBox;
import javafx.scene.layout.HBox;

import java.time.DayOfWeek;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The T2..CN weekday checkbox row shared by the task-recurrence and
 * habit-schedule editors. Each checkbox carries its {@link DayOfWeek} as
 * userData; {@link #selected} collects the checked days Monday-first.
 */
public final class WeekdayChecks {
    private static final String[] DAY_NAMES = {"T2", "T3", "T4", "T5", "T6", "T7", "CN"};

    private WeekdayChecks() {
    }

    /**
     * Build the row and append its checkboxes to {@code out} (the caller keeps
     * the list to read selections later). Days in {@code selected} are
     * pre-checked; pass null for none.
     */
    public static HBox row(Set<DayOfWeek> selected, List<CheckBox> out) {
        HBox box = new HBox(6);
        for (DayOfWeek dow : DayOfWeek.values()) {
            CheckBox cb = new CheckBox(DAY_NAMES[dow.getValue() - 1]);
            cb.setUserData(dow);
            cb.setSelected(selected != null && selected.contains(dow));
            out.add(cb);
            box.getChildren().add(cb);
        }
        return box;
    }

    /** The checked days, Monday-first (input order of the checkboxes). */
    public static Set<DayOfWeek> selected(List<CheckBox> checks) {
        Set<DayOfWeek> out = new LinkedHashSet<>();
        for (CheckBox cb : checks) {
            if (cb.isSelected()) {
                out.add((DayOfWeek) cb.getUserData());
            }
        }
        return out;
    }
}
