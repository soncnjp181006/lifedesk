package com.lifedesk.storage;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects storage problems so the UI can display them (no silent failures). */
public class StorageIssueLog {
    private final List<StorageIssue> issues = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Runnable l) {
        listeners.add(l);
    }

    public void report(StorageIssue issue) {
        issues.add(0, issue);
        if (issues.size() > 100) {
            issues.remove(issues.size() - 1);
        }
        for (Runnable l : listeners) {
            l.run();
        }
    }

    public void error(String path, String message) {
        report(new StorageIssue(path, StorageIssue.ERROR, message, Instant.now()));
    }

    public void warning(String path, String message) {
        report(new StorageIssue(path, StorageIssue.WARNING, message, Instant.now()));
    }

    public List<StorageIssue> issues() {
        return List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> StorageIssue.ERROR.equals(i.severity()));
    }
}
