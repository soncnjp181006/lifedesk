package com.lifedesk.tasks;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** JSON file-backed task repository (data/tasks/tasks.json). */
public class JsonTaskRepository implements TaskRepository {
    public static final String FILE = "tasks/tasks.json";
    private static final int SCHEMA_VERSION = 1;
    private static final Type TASK_LIST = new TypeToken<List<Task>>() {
    }.getType();

    private static final SchemaMigrator MIGRATOR = new SchemaMigrator() {
        @Override
        public int currentVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public JsonObject migrate(JsonObject data, int fromVersion) {
            return data; // v1 is the first version; future versions add explicit steps here.
        }
    };

    private final JsonStore store;
    private final List<Task> tasks;

    public JsonTaskRepository(JsonStore store) {
        this.store = store;
        this.tasks = load();
    }

    private List<Task> load() {
        JsonStore.StoredDoc doc = store.read(FILE, MIGRATOR);
        if (doc == null) {
            return new ArrayList<>();
        }
        if (!doc.data().has("tasks") || !doc.data().get("tasks").isJsonArray()) {
            return new ArrayList<>();
        }
        List<Task> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray("tasks"), TASK_LIST);
        return parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
    }

    private void persist() {
        JsonObject data = new JsonObject();
        data.add("tasks", GsonFactory.create().toJsonTree(tasks));
        store.write(FILE, SCHEMA_VERSION, data);
    }

    @Override
    public List<Task> findAll() {
        return List.copyOf(tasks);
    }

    @Override
    public Optional<Task> findById(String id) {
        return tasks.stream().filter(t -> t.id.equals(id)).findFirst();
    }

    @Override
    public void insert(Task task) {
        tasks.add(task);
        persist();
    }

    @Override
    public void update(Task task) {
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
    public void delete(String id) {
        if (!tasks.removeIf(t -> t.id.equals(id))) {
            throw new com.lifedesk.common.NotFoundException("Không tìm thấy công việc: " + id);
        }
        persist();
    }

    /** Sorted copy used by views: by due date, then priority, then created. */
    public List<Task> sorted() {
        Comparator<Task> byPriority = Comparator.comparingInt(t -> -t.priority.ordinal());
        return tasks.stream()
                .sorted(Comparator.comparing(Task::getDueDateOrMax)
                        .thenComparing(byPriority)
                        .thenComparing(t -> t.createdAt))
                .toList();
    }

    public int count() {
        return tasks.size();
    }
}
