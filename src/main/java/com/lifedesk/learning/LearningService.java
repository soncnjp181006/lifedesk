package com.lifedesk.learning;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Learning module: courses, study sessions and Leitner-box flashcards.
 * Review schedule: box 1..6 with intervals 1,2,4,7,15,30 days.
 */
public class LearningService {
    /** Leitner intervals in days, indexed by box-1. Box 7 means graduated. */
    public static final int[] BOX_INTERVALS = {1, 2, 4, 7, 15, 30};

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();
    private final List<Course> courses = new ArrayList<>();
    private final List<StudySession> sessions = new ArrayList<>();
    private final List<Deck> decks = new ArrayList<>();
    private final List<Flashcard> cards = new ArrayList<>();

    public LearningService(JsonStore store) {
        this.store = store;
        Type courseType = new TypeToken<List<Course>>() {
        }.getType();
        Type sessionType = new TypeToken<List<StudySession>>() {
        }.getType();
        Type deckType = new TypeToken<List<Deck>>() {
        }.getType();
        Type cardType = new TypeToken<List<Flashcard>>() {
        }.getType();
        courses.addAll(readList("learning/courses.json", "courses", courseType));
        sessions.addAll(readList("learning/sessions.json", "sessions", sessionType));
        decks.addAll(readList("learning/decks.json", "decks", deckType));
        cards.addAll(readList("learning/flashcards.json", "cards", cardType));
    }

    public ChangeNotifier changes() {
        return change;
    }

    private <T> List<T> readList(String file, String key, Type listType) {
        JsonStore.StoredDoc doc = store.read(file, SchemaMigrator.none(1));
        if (doc == null || !doc.data().has(key) || !doc.data().get(key).isJsonArray()) {
            return new ArrayList<>();
        }
        List<T> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray(key), listType);
        return parsed == null ? new ArrayList<>() : parsed;
    }

    private void write(String file, String key, Object list) {
        JsonObject data = new JsonObject();
        data.add(key, GsonFactory.create().toJsonTree(list));
        store.write(file, 1, data);
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

    // ------------------------------------------------------------------ study sessions

    public List<StudySession> sessions() {
        return sessions.stream().sorted(Comparator.comparing((StudySession s) -> s.date).reversed()).toList();
    }

    public List<StudySession> sessionsFor(String courseId) {
        return sessions.stream().filter(s -> s.courseId.equals(courseId))
                .sorted(Comparator.comparing((StudySession s) -> s.date).reversed()).toList();
    }

    public void addSession(String courseId, LocalDate date, int minutes, String topic) {
        course(courseId);
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
        write("learning/courses.json", "courses", courses);
    }

    private void persistSessions() {
        write("learning/sessions.json", "sessions", sessions);
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
        write("learning/decks.json", "decks", decks);
    }

    private void persistCards() {
        write("learning/flashcards.json", "cards", cards);
    }
}
