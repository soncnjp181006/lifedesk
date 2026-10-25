package com.lifedesk.automation;

/** Permission tier of an agent tool. */
public enum PermissionLevel {
    /** Read-only within granted scope; may run without confirmation. */
    READ,
    /** Creates or updates app data; requires the whole plan to be approved before start. */
    WRITE,
    /** Deletes or overwrites data; requires explicit per-step confirmation at run time. */
    DESTRUCTIVE
}
