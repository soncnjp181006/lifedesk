package com.lifedesk.tasks;

import java.util.List;

/** Repository contract for the group names a user maintains by hand. */
public interface TaskGroupRepository {
    List<String> findAll();

    void save(List<String> names);
}
