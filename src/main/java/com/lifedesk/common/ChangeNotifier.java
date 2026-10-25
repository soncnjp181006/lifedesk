package com.lifedesk.common;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight change notification for services (no JavaFX dependency).
 * UI layers subscribe and refresh their views when notified.
 */
public class ChangeNotifier {
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void fire() {
        for (Runnable r : listeners) {
            r.run();
        }
    }
}
