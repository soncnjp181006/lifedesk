package com.lifedesk.storage;

import java.time.Instant;

/** A persistence problem that must be surfaced to the user, never swallowed silently. */
public record StorageIssue(String path, String severity, String message, Instant at) {
    public static final String WARNING = "WARNING";
    public static final String ERROR = "ERROR";
}
