package com.lifedesk.tasks;

import com.google.gson.reflect.TypeToken;
import com.lifedesk.storage.JsonStore;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** JSON file-backed group name list (data/tasks/groups.json). */
public class JsonTaskGroupRepository implements TaskGroupRepository {
    public static final String FILE = "tasks/groups.json";
    private static final int SCHEMA_VERSION = 1;
    private static final Type NAME_LIST = new TypeToken<List<String>>() {
    }.getType();

    private final JsonStore store;
    private final List<String> names;

    public JsonTaskGroupRepository(JsonStore store) {
        this.store = store;
        this.names = load();
    }

    private List<String> load() {
        return new ArrayList<>(store.readList(FILE, "groups", NAME_LIST));
    }

    @Override
    public List<String> findAll() {
        return List.copyOf(names);
    }

    @Override
    public void save(List<String> toSave) {
        names.clear();
        names.addAll(toSave);
        store.writeList(FILE, "groups", SCHEMA_VERSION, names);
    }
}
