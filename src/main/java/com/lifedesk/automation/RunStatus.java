package com.lifedesk.automation;

/** Lifecycle of an agent run. */
public enum RunStatus {
    PENDING, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED
}
