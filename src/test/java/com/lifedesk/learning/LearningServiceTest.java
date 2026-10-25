package com.lifedesk.learning;

import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.StorageIssueLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class LearningServiceTest {

    @TempDir
    Path dir;

    private LearningService service() {
        return new LearningService(new JsonStore(dir, new StorageIssueLog()));
    }

    @Test
    void deckRenamePersists() {
        LearningService svc = service();
        Deck d = svc.addDeck("Old", "");
        svc.renameDeck(d.id, "  Toeic 500  ");
        assertEquals("Toeic 500", svc.deck(d.id).name);
        assertThrows(RuntimeException.class, () -> svc.renameDeck(d.id, "   "));
    }

    @Test
    void cardUpdateEditsContent() {
        LearningService svc = service();
        Deck d = svc.addDeck("Deck", "");
        Flashcard c = svc.addCard(d.id, "front1", "back1");
        svc.updateCard(c.id, "front2", "back2");
        assertEquals("front2", svc.card(c.id).front);
        assertEquals("back2", svc.card(c.id).back);
        assertThrows(RuntimeException.class, () -> svc.updateCard(c.id, " ", "back"));
        assertThrows(RuntimeException.class, () -> svc.updateCard("missing", "a", "b"));
    }

    @Test
    void sessionUpdateAndDelete() {
        LearningService svc = service();
        Course course = svc.addCourse("Java", "sách", "", null);
        svc.addSession(course.id, LocalDate.now(), 30, "chuong 1");
        String sessionId = svc.sessionsFor(course.id).get(0).id;

        svc.updateSession(sessionId, LocalDate.now().minusDays(1), 45, "chuong 1-2");
        var updated = svc.sessionsFor(course.id).get(0);
        assertEquals(45, updated.minutes);
        assertEquals(LocalDate.now().minusDays(1), updated.date);
        assertEquals("chuong 1-2", updated.topic);

        assertThrows(RuntimeException.class, () -> svc.updateSession(sessionId, LocalDate.now(), 0, "x"));
        svc.deleteSession(sessionId);
        assertTrue(svc.sessionsFor(course.id).isEmpty());
        assertThrows(RuntimeException.class, () -> svc.deleteSession(sessionId));
    }

    @Test
    void reviewScheduleAccumulates() {
        LearningService svc = service();
        Deck d = svc.addDeck("D", "");
        Flashcard c = svc.addCard(d.id, "q", "a");
        int[] intervals = {1, 2, 4, 7, 15, 30};
        for (int i = 0; i < intervals.length; i++) {
            svc.review(c.id, true);
            assertEquals(Math.min(i + 2, 6), svc.card(c.id).box);
        }
        // wrong answer drops back to box 1, due tomorrow
        svc.review(c.id, false);
        assertEquals(1, svc.card(c.id).box);
        assertEquals(LocalDate.now().plusDays(1), svc.card(c.id).dueDate);
    }
}
