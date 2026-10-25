package com.lifedesk.learning;

import java.time.LocalDate;

/** One study session logged against a course. */
public class StudySession {
    public String id;
    public String courseId;
    public LocalDate date;
    public int minutes;
    public String topic = "";

    public StudySession() {
        this.id = com.lifedesk.common.Ids.newId();
    }

    public StudySession(String courseId, LocalDate date, int minutes, String topic) {
        this();
        this.courseId = courseId;
        this.date = date;
        this.minutes = minutes;
        this.topic = topic;
    }
}
