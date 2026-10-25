package com.lifedesk.learning;

import java.time.Instant;
import java.time.LocalDate;

/** A flashcard with Leitner-box spaced repetition scheduling. */
public class Flashcard {
    public String id;
    public String deckId = "";
    public String front = "";
    public String back = "";
    /** Leitner box 1..6. */
    public int box = 1;
    public LocalDate dueDate = LocalDate.now();
    public int timesCorrect = 0;
    public int timesWrong = 0;
    public Instant createdAt = Instant.now();

    public Flashcard() {
        this.id = com.lifedesk.common.Ids.newId();
    }
}
