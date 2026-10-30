package com.lifedesk.search;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.TextSearch;
import com.lifedesk.finance.FinanceService;
import com.lifedesk.habits.HabitService;
import com.lifedesk.health.HealthService;
import com.lifedesk.learning.LearningService;
import com.lifedesk.notes.NotesService;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import com.lifedesk.tasks.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-module search index. Fully offline; the index is derived from the
 * original module data and can be rebuilt at any time (also persisted to
 * data/index/search-index.json as a cache). Search runs on a background
 * executor so the UI never blocks.
 *
 * <p>Each module contributes documents via one small {@code indexX} method;
 * {@link #add} normalizes the searchable text once at index time so queries
 * only score against cached strings instead of re-normalizing every document
 * on every query.
 */
public class SearchService {
    private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);
    private static final String INDEX_FILE = "index/search-index.json";

    /** A document plus its normalized searchable text (computed once per rebuild). */
    private record IndexedDoc(SearchDoc doc, String title, String tags, String content) {
    }

    private final JsonStore store;
    private final TaskService taskService;
    private final NotesService notesService;
    private final FinanceService financeService;
    private final HabitService habitService;
    private final HealthService healthService;
    private final LearningService learningService;

    private final Map<String, SearchDoc> docs = new HashMap<>();
    private final Map<String, IndexedDoc> indexed = new HashMap<>();

    public SearchService(JsonStore store, TaskService taskService, NotesService notesService,
                         FinanceService financeService, HabitService habitService,
                         HealthService healthService, LearningService learningService) {
        this.store = store;
        this.taskService = taskService;
        this.notesService = notesService;
        this.financeService = financeService;
        this.habitService = habitService;
        this.healthService = healthService;
        this.learningService = learningService;
    }

    /** Rebuild all documents from module sources (background thread safe: synchronize). */
    public synchronized void rebuildAll() {
        long start = System.currentTimeMillis();
        Map<String, SearchDoc> fresh = new HashMap<>();
        indexTasks(fresh);
        indexNotes(fresh);
        indexFinance(fresh);
        indexHabits(fresh);
        indexHealth(fresh);
        indexLearning(fresh);
        docs.clear();
        docs.putAll(fresh);
        reindexNormalized();
        persist();
        LOG.info("Search index rebuilt: {} docs in {} ms", docs.size(), System.currentTimeMillis() - start);
    }

    private void reindexNormalized() {
        indexed.clear();
        for (SearchDoc doc : docs.values()) {
            indexed.put(doc.docId(), new IndexedDoc(doc,
                    TextSearch.normalize(doc.title()),
                    TextSearch.normalize(doc.tags()),
                    TextSearch.normalize(doc.content())));
        }
    }

    /** Put a document into {@code out}, computing the docId once. */
    private void add(Map<String, SearchDoc> out, String type, String id,
                     String title, String content, String tags) {
        out.put(docId(type, id), new SearchDoc(docId(type, id), type, title, content, tags, id));
    }

    private void indexTasks(Map<String, SearchDoc> out) {
        for (var t : taskService.all()) {
            String content = t.notes + " " + t.checklist.stream().map(c -> c.text).reduce("", (a, b) -> a + " " + b);
            add(out, SearchDoc.TASK, t.id, t.title, content, String.join(" ", t.tags));
        }
    }

    private void indexNotes(Map<String, SearchDoc> out) {
        for (var n : notesService.list()) {
            String content = notesService.readContent(n.id);
            // Bound huge notes in the index; full-text search in NotesService remains exhaustive.
            if (content.length() > 20000) {
                content = content.substring(0, 20000);
            }
            add(out, SearchDoc.NOTE, n.id, n.title, content, String.join(" ", n.tags));
        }
    }

    private void indexFinance(Map<String, SearchDoc> out) {
        for (var t : financeService.all()) {
            String categoryName = safeCall(() -> financeService.categoryById(t.categoryId).name);
            add(out, SearchDoc.TRANSACTION, t.id,
                    t.date + " " + t.type + " " + categoryName,
                    t.description + " " + t.amount.toPlainString(),
                    String.join(" ", t.tags));
        }
    }

    private void indexHabits(Map<String, SearchDoc> out) {
        for (var h : habitService.habits(true)) {
            add(out, SearchDoc.HABIT, h.id, h.name, h.description, "");
        }
        for (var g : habitService.goals()) {
            add(out, SearchDoc.GOAL, g.id, g.title, g.description, "");
        }
    }

    private void indexHealth(Map<String, SearchDoc> out) {
        for (var e : healthService.entries()) {
            String content = "ngủ " + e.sleepHours + " tập " + e.exerciseMinutes + " cân " + e.weightKg + " " + e.note;
            add(out, SearchDoc.HEALTH, e.id, "Sức khỏe " + e.date, content, "");
        }
    }

    private void indexLearning(Map<String, SearchDoc> out) {
        for (var c : learningService.courses()) {
            add(out, SearchDoc.COURSE, c.id, c.title, c.source + " " + c.link, String.join(" ", c.tags));
        }
        for (var deck : learningService.decks()) {
            for (var card : learningService.cardsInDeck(deck.id)) {
                add(out, SearchDoc.FLASHCARD, card.id, card.front, card.back, "");
            }
        }
    }

    private String safeCall(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (Exception e) {
            return "";
        }
    }

    private String docId(String type, String id) {
        return type + ":" + id;
    }

    /** Query with AND semantics over terms; optional type filter (null = all). */
    public synchronized List<SearchGroup> query(String query, Set<String> typeFilter, int maxPerGroup) {
        List<String> terms = TextSearch.terms(query);
        Map<String, Integer> scores = new HashMap<>();
        for (IndexedDoc d : indexed.values()) {
            if (typeFilter != null && !typeFilter.isEmpty() && !typeFilter.contains(d.doc().type())) {
                continue;
            }
            int score = 0;
            boolean all = !terms.isEmpty();
            for (String term : terms) {
                int s = 0;
                if (d.title().contains(term)) {
                    s += 3;
                }
                if (d.tags().contains(term)) {
                    s += 2;
                }
                if (d.content().contains(term)) {
                    s += 1;
                }
                if (s == 0) {
                    all = false;
                    break;
                }
                score += s;
            }
            if (all) {
                scores.put(d.doc().docId(), score);
            }
        }
        Map<String, List<SearchResult>> byType = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(200)
                .toList()) {
            SearchDoc doc = docs.get(e.getKey());
            if (doc == null) {
                continue;
            }
            byType.computeIfAbsent(doc.type(), k -> new ArrayList<>())
                    .add(new SearchResult(doc, e.getValue()));
        }
        List<SearchGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<SearchResult>> e : byType.entrySet()) {
            e.getValue().sort(Comparator.comparing(SearchResult::score).reversed());
            if (e.getValue().size() > maxPerGroup) {
                e.getValue().subList(maxPerGroup, e.getValue().size()).clear();
            }
            groups.add(new SearchGroup(e.getKey(), SearchDoc.displayType(e.getKey()), e.getValue()));
        }
        groups.sort(Comparator.comparingInt(g -> -g.results().size()));
        return groups;
    }

    // ------------------------------------------------------------------ persistence (cache)

    private void persist() {
        JsonObject data = new JsonObject();
        data.add("docs", GsonFactory.create().toJsonTree(new ArrayList<>(docs.values())));
        try {
            store.write(INDEX_FILE, 1, data);
        } catch (RuntimeException e) {
            LOG.warn("Index persistence failed (index remains in memory, rebuildable): {}", e.toString());
        }
    }

    /** Load the persisted cache (fast startup); call rebuildAll() for a fresh index. */
    public synchronized void loadPersisted() {
        Type listType = new TypeToken<List<SearchDoc>>() {
        }.getType();
        JsonStore.StoredDoc doc = store.read(INDEX_FILE, SchemaMigrator.none(1));
        if (doc == null || !doc.data().has("docs")) {
            return;
        }
        List<SearchDoc> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray("docs"), listType);
        if (parsed == null) {
            return;
        }
        docs.clear();
        for (SearchDoc d : parsed) {
            docs.put(d.docId(), d);
        }
        reindexNormalized();
        LOG.info("Loaded persisted search index: {} docs", docs.size());
    }
}
