package com.lifedesk.learning;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** A course or learning material the user follows. */
public class Course {
    public String id;
    public String title = "";
    public String source = "";
    public CourseStatus status = CourseStatus.PLANNED;
    /** Manual progress 0..100 (user editable). */
    public int progressPercent = 0;
    /** Local file path or web URL of the material. */
    public String link = "";
    public Set<String> tags = new LinkedHashSet<>();
    /** Linked Markdown note ids. */
    public Set<String> linkedNoteIds = new LinkedHashSet<>();
    /** Local material files (lectures, slides, books...) managed by the Learning module. */
    public java.util.List<String> materialPaths = new java.util.ArrayList<>();
    public Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    public Course() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    // Accessors (UI and rule engine read courses fluently)
    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String source() {
        return source;
    }

    public String link() {
        return link;
    }

    public CourseStatus status() {
        return status;
    }

    public int progressPercent() {
        return progressPercent;
    }

    public java.util.Set<String> linkedNoteIds() {
        return linkedNoteIds;
    }
}
