package com.lifedesk.learning;

import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;
import com.lifedesk.storage.JsonStore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Learning module: courses, study sessions and Leitner-box flashcards.
 * Review schedule: box 1..6 with intervals 1,2,4,7,15,30 days.
 */
public class LearningService {
    /** Leitner intervals in days, indexed by box-1. Boxes top out at 6 (no graduation state). */
    public static final int[] BOX_INTERVALS = {1, 2, 4, 7, 15, 30};

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();
    private final List<Course> courses = new ArrayList<>();
    private final List<StudySession> sessions = new ArrayList<>();
    private final List<Deck> decks = new ArrayList<>();
    private final List<Flashcard> cards = new ArrayList<>();

    public LearningService(JsonStore store) {
        this.store = store;
        courses.addAll(store.readList("learning/courses.json", "courses",
                new TypeToken<List<Course>>() {
                }.getType()));
        sessions.addAll(store.readList("learning/sessions.json", "sessions",
                new TypeToken<List<StudySession>>() {
                }.getType()));
        decks.addAll(store.readList("learning/decks.json", "decks",
                new TypeToken<List<Deck>>() {
                }.getType()));
        cards.addAll(store.readList("learning/flashcards.json", "cards",
                new TypeToken<List<Flashcard>>() {
                }.getType()));
    }

    public ChangeNotifier changes() {
        return change;
    }

    // ------------------------------------------------------------------ courses

    public List<Course> courses() {
        return courses.stream()
                .sorted(Comparator.comparing((Course c) -> c.status).thenComparing(c -> c.title))
                .toList();
    }

    public Course course(String id) {
        return courses.stream().filter(c -> c.id.equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy khóa học: " + id));
    }

    public Course addCourse(String title, String source, String link, java.util.Set<String> tags) {
        Course c = new Course();
        c.title = Validators.requireNonBlank(title, "Tên khóa học");
        c.source = source == null ? "" : source;
        c.link = link == null ? "" : link;
        if (tags != null) {
            c.tags.addAll(tags);
        }
        c.status = CourseStatus.PLANNED;
        courses.add(c);
        persistCourses();
        change.fire();
        return c;
    }

    public void updateCourse(Course updated) {
        course(updated.id); // existence check
        if (updated.progressPercent < 0 || updated.progressPercent > 100) {
            throw new ValidationException("Tiến độ phải từ 0 đến 100");
        }
        updated.updatedAt = java.time.Instant.now();
        for (int i = 0; i < courses.size(); i++) {
            if (courses.get(i).id.equals(updated.id)) {
                courses.set(i, updated);
                persistCourses();
                change.fire();
                return;
            }
        }
    }

    public void deleteCourse(String id) {
        if (!courses.removeIf(c -> c.id.equals(id))) {
            throw new NotFoundException("Không tìm thấy khóa học: " + id);
        }
        sessions.removeIf(s -> s.courseId.equals(id));
        persistCourses();
        persistSessions();
        change.fire();
    }

    public void linkNote(String courseId, String noteId) {
        Course c = course(courseId);
        c.linkedNoteIds.add(noteId);
        persistCourses();
        change.fire();
    }

    public void unlinkNote(String courseId, String noteId) {
        Course c = course(courseId);
        c.linkedNoteIds.remove(noteId);
        persistCourses();
        change.fire();
    }

    /** Register a material file for a course. */
    public void addMaterial(String courseId, String path) {
        Course c = course(courseId);
        if (!c.materialPaths.contains(path)) {
            c.materialPaths.add(path);
        }
        persistCourses();
        change.fire();
    }

    public void removeMaterial(String courseId, String path) {
        Course c = course(courseId);
        c.materialPaths.remove(path);
        persistCourses();
        change.fire();
    }

    public java.util.List<String> materials(String courseId) {
        return java.util.List.copyOf(course(courseId).materialPaths);
    }

    public int dueCountForDeck(String deckId) {
        LocalDate today = LocalDate.now();
        return (int) cards.stream()
                .filter(c -> c.deckId.equals(deckId) && !c.dueDate.isAfter(today))
                .count();
    }

    // ------------------------------------------------------------------ study sessions

    public List<StudySession> sessions() {
        return sessions.stream().sorted(Comparator.comparing((StudySession s) -> s.date).reversed()).toList();
    }

    public List<StudySession> sessionsFor(String courseId) {
        return sessions.stream().filter(s -> s.courseId.equals(courseId))
                .sorted(Comparator.comparing((StudySession s) -> s.date).reversed()).toList();
    }

    public void addSession(String courseId, LocalDate date, int minutes, String topic) {
        // Deck-based flashcard review is not tied to a course — courseId may be
        // null (it used to throw "Không tìm thấy khóa học: null" and crash the
        // review session the moment a study session was logged).
        if (courseId != null) {
            course(courseId);
        }
        if (minutes <= 0 || minutes > 24 * 60) {
            throw new ValidationException("Thời lượng học phải từ 1 đến 1440 phút");
        }
        sessions.add(new StudySession(courseId, date, minutes, topic == null ? "" : topic));
        persistSessions();
        change.fire();
    }

    /** Total minutes studied between two dates. */
    public long minutesBetween(LocalDate from, LocalDate to) {
        return sessions.stream()
                .filter(s -> !s.date.isBefore(from) && !s.date.isAfter(to))
                .mapToLong(s -> s.minutes)
                .sum();
    }

    private void persistCourses() {
        store.writeList("learning/courses.json", "courses", 1, courses);
    }

    private void persistSessions() {
        store.writeList("learning/sessions.json", "sessions", 1, sessions);
    }

    // ------------------------------------------------------------------ decks & flashcards

    public List<Deck> decks() {
        return decks.stream().sorted(Comparator.comparing(d -> d.name)).toList();
    }

    public Deck deck(String id) {
        return decks.stream().filter(d -> d.id.equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy bộ thẻ: " + id));
    }

    public Deck addDeck(String name, String courseId) {
        Deck d = new Deck(Validators.requireNonBlank(name, "Tên bộ thẻ"), courseId);
        decks.add(d);
        persistDecks();
        change.fire();
        return d;
    }

    public void renameDeck(String id, String newName) {
        deck(id).name = Validators.requireNonBlank(newName, "Tên bộ thẻ");
        persistDecks();
        change.fire();
    }

    public void updateCard(String cardId, String front, String back) {
        Flashcard card = cards.stream().filter(c -> c.id.equals(cardId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy thẻ: " + cardId));
        if (front == null || front.isBlank() || back == null || back.isBlank()) {
            throw new ValidationException("Mặt trước và mặt sau của thẻ không được để trống");
        }
        card.front = front.trim();
        card.back = back.trim();
        persistCards();
        change.fire();
    }

    public Flashcard card(String cardId) {
        return cards.stream().filter(c -> c.id.equals(cardId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy thẻ: " + cardId));
    }

    public void updateSession(String sessionId, LocalDate date, int minutes, String topic) {
        StudySession session = sessions.stream().filter(x -> x.id.equals(sessionId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy buổi học: " + sessionId));
        if (minutes <= 0 || minutes > 24 * 60) {
            throw new ValidationException("Thời lượng học phải từ 1 đến 1440 phút");
        }
        session.date = Validators.requireNonNull(date, "Ngày học");
        session.minutes = minutes;
        session.topic = topic == null ? "" : topic;
        persistSessions();
        change.fire();
    }

    public void deleteSession(String sessionId) {
        if (!sessions.removeIf(s -> s.id.equals(sessionId))) {
            throw new NotFoundException("Không tìm thấy buổi học: " + sessionId);
        }
        persistSessions();
        change.fire();
    }

    public void deleteDeck(String id) {
        if (!decks.removeIf(d -> d.id.equals(id))) {
            throw new NotFoundException("Không tìm thấy bộ thẻ: " + id);
        }
        cards.removeIf(c -> c.deckId.equals(id));
        persistDecks();
        persistCards();
        change.fire();
    }

    public List<Flashcard> cardsInDeck(String deckId) {
        return cards.stream().filter(c -> c.deckId.equals(deckId))
                .sorted(Comparator.comparing(c -> c.dueDate)).toList();
    }

    public Flashcard addCard(String deckId, String front, String back) {
        deck(deckId);
        if (front == null || front.isBlank() || back == null || back.isBlank()) {
            throw new ValidationException("Mặt trước và mặt sau của thẻ không được để trống");
        }
        Flashcard card = new Flashcard();
        card.deckId = deckId;
        card.front = front.trim();
        card.back = back.trim();
        card.box = 1;
        card.dueDate = LocalDate.now();
        cards.add(card);
        persistCards();
        change.fire();
        return card;
    }

    public void deleteCard(String id) {
        if (!cards.removeIf(c -> c.id.equals(id))) {
            throw new NotFoundException("Không tìm thấy thẻ: " + id);
        }
        persistCards();
        change.fire();
    }

    /** Cards due for review (dueDate <= today). */
    public List<Flashcard> dueCards(String deckId) {
        LocalDate today = LocalDate.now();
        return cards.stream()
                .filter(c -> deckId == null || c.deckId.equals(deckId))
                .filter(c -> !c.dueDate.isAfter(today))
                .sorted(Comparator.comparing((Flashcard c) -> c.dueDate).thenComparing(c -> c.createdAt))
                .toList();
    }

    /** Record a review answer and advance the Leitner schedule. */
    public void review(String cardId, boolean correct) {
        Flashcard card = cards.stream().filter(c -> c.id.equals(cardId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy thẻ: " + cardId));
        if (correct) {
            card.timesCorrect++;
            card.box = Math.min(card.box + 1, BOX_INTERVALS.length);
        } else {
            card.timesWrong++;
            card.box = 1;
        }
        int interval = BOX_INTERVALS[Math.min(card.box, BOX_INTERVALS.length) - 1];
        card.dueDate = LocalDate.now().plusDays(correct ? interval : 1);
        persistCards();
        change.fire();
    }

    public int cardCount() {
        return cards.size();
    }

    public int dueCount() {
        return dueCards(null).size();
    }

    private void persistDecks() {
        store.writeList("learning/decks.json", "decks", 1, decks);
    }

    private void persistCards() {
        store.writeList("learning/flashcards.json", "cards", 1, cards);
    }
}
