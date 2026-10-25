package com.lifedesk.tasks;

import java.util.List;
import java.util.Optional;

/** Repository contract for tasks. UI never touches files; services talk to this interface. */
public interface TaskRepository {
    List<Task> findAll();

    Optional<Task> findById(String id);

    void insert(Task task);

    void update(Task task);

    void delete(String id);
}
