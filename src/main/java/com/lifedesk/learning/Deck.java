package com.lifedesk.learning;

/** A flashcard deck (e.g. per course or per topic). */
public class Deck {
    public String id;
    public String name = "";
    public String courseId = "";

    public Deck() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public Deck(String name, String courseId) {
        this();
        this.name = name;
        this.courseId = courseId == null ? "" : courseId;
    }
}
