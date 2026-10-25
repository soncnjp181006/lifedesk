package com.lifedesk.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Application-wide background executors with a managed lifecycle.
 * All file I/O, search indexing and AI calls run off the JavaFX Application Thread.
 */
public class AppExecutors {
    private static final Logger LOG = LoggerFactory.getLogger(AppExecutors.class);

    private final ExecutorService io;
    private final ExecutorService ai;
    private final ScheduledExecutorService scheduler;
    private final List<Runnable> ioErrorListeners = new CopyOnWriteArrayList<>();

    public AppExecutors() {
        io = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lifedesk-io");
            t.setDaemon(true);
            return t;
        });
        ai = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "lifedesk-ai");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "lifedesk-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public ExecutorService io() {
        return io;
    }

    public ExecutorService ai() {
        return ai;
    }

    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /** Listeners get notified when a background io task dies unexpectedly. */
    public void addIoErrorListener(Runnable listener) {
        ioErrorListeners.add(listener);
    }

    public void shutdown() {
        shutdownPool(io, "io");
        shutdownPool(ai, "ai");
        shutdownPool(scheduler, "scheduler");
    }

    private void shutdownPool(ExecutorService pool, String name) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                LOG.warn("{} pool did not terminate cleanly; forcing shutdown", name);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }
}
