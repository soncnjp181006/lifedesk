package com.lifedesk.common;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight change notification for services (no JavaFX dependency).
 * UI layers subscribe and refresh their views when notified.
 */
public class ChangeNotifier {
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /**
     * Monotonic change counter. A view can compare the value it painted against
     * the current one and skip a rebuild that would produce identical pixels —
     * re-reading and re-laying-out a table on every visit was several milliseconds
     * of pure waste on repeat navigation.
     */
    private volatile long revision = 0;

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public long revision() {
        return revision;
    }

    public void fire() {
        revision++;
        for (Runnable r : listeners) {
            r.run();
        }
    }
}
