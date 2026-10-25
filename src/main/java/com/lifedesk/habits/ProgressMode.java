package com.lifedesk.habits;

/** How goal progress is computed. */
public enum ProgressMode {
    /** Manual percentage set by the user. */
    MANUAL,
    /** Derived from linked tasks: done / total. */
    AUTO_TASKS
}
