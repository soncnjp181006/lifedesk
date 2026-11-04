package com.lifedesk.tasks;

import com.google.gson.reflect.TypeToken;
import com.lifedesk.storage.JsonStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JSON file-backed task repository (data/tasks/tasks.json).
 * The API answers on a thread pool, so reads and writes of the list are
 * serialised: an unsynchronised add() racing a set() drops rows silently.
 */
public class JsonTaskRepository implements TaskRepository {
    public static final String FILE = "tasks/tasks.json";
    private static final int SCHEMA_VERSION = 1;
    private static final java.lang.reflect.Type TASK_LIST = new TypeToken<List<Task>>() {
    }.getType();

    private final JsonStore store;
    private final List<Task> tasks;

    public JsonTaskRepository(JsonStore store) {
        this.store = store;
        this.tasks = load();
    }

    private List<Task> load() {
        return new ArrayList<>(store.readList(FILE, "tasks", TASK_LIST));
    }

    private void persist() {
        store.writeList(FILE, "tasks", SCHEMA_VERSION, tasks);
    }

    @Override
    public synchronized List<Task> findAll() {
        return List.copyOf(tasks);
    }

    @Override
    public synchronized Optional<Task> findById(String id) {
        return tasks.stream().filter(t -> t.id.equals(id)).findFirst();
    }

    @Override
    public synchronized void insert(Task task) {
        tasks.add(task);
        persist();
    }

    @Override
    public synchronized void update(Task task) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id.equals(task.id)) {
                task.updatedAt = java.time.Instant.now();
                tasks.set(i, task);
                persist();
                return;
            }
        }
        throw new com.lifedesk.common.NotFoundException("Không tìm thấy công việc: " + task.id);
    }

    @Override
    public synchronized void delete(String id) {
        if (!tasks.removeIf(t -> t.id.equals(id))) {
            throw new com.lifedesk.common.NotFoundException("Không tìm thấy công việc: " + id);
        }
        persist();
    }

}
