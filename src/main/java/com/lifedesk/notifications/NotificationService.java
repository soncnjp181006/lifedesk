package com.lifedesk.notifications;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;

import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;

/** In-app notification center (no OS toasts — everything stays local). */
public class NotificationService {
    private static final int MAX_ITEMS = 200;

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();
    private final Deque<NotificationItem> items = new ArrayDeque<>();

    public NotificationService(JsonStore store) {
        this.store = store;
        load();
    }

    public ChangeNotifier changes() {
        return change;
    }

    private void load() {
        Type listType = new TypeToken<java.util.List<NotificationItem>>() {
        }.getType();
        JsonStore.StoredDoc doc = store.read("notifications/notifications.json", SchemaMigrator.none(1));
        if (doc != null && doc.data().has("items")) {
            java.util.List<NotificationItem> parsed =
                    GsonFactory.create().fromJson(doc.data().getAsJsonArray("items"), listType);
            if (parsed != null) {
                parsed.forEach(items::addLast);
            }
        }
    }

    private void persist() {
        JsonObject data = new JsonObject();
        data.add("items", GsonFactory.create().toJsonTree(new java.util.ArrayList<>(items)));
        store.write("notifications/notifications.json", 1, data);
    }

    public synchronized NotificationItem push(String title, String message, String severity, String refView, String refId) {
        NotificationItem item = new NotificationItem(title, message, severity, refView, refId);
        items.addFirst(item);
        while (items.size() > MAX_ITEMS) {
            items.removeLast();
        }
        persist();
        change.fire();
        return item;
    }

    public synchronized java.util.List<NotificationItem> all() {
        return new java.util.ArrayList<>(items);
    }

    public synchronized int unreadCount() {
        return (int) items.stream().filter(i -> !i.read).count();
    }

    public synchronized void markAllRead() {
        boolean changed = false;
        for (NotificationItem i : items) {
            if (!i.read) {
                i.read = true;
                changed = true;
            }
        }
        if (changed) {
            persist();
            change.fire();
        }
    }

    public synchronized void clear() {
        items.clear();
        persist();
        change.fire();
    }
}
