package com.lifedesk.notes;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** Note metadata (content lives in a separate .md file). */
public class NoteMeta {
    public String id;
    public String title = "";
    /** Relative folder inside data/notes ("" = root). e.g. "work/projects". */
    public String folder = "";
    public Set<String> tags = new LinkedHashSet<>();
    /** Relative path of the markdown content file, from the data root. */
    public String contentFile = "";
    public boolean starred = false;
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    /** SHA-256 of the content at the last successful save (conflict detection). */
    public String lastSavedHash = "";

    public NoteMeta() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }
}
