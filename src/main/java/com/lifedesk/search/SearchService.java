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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Cross-module search index. Fully offline; the index is derived from the
 * original module data and can be rebuilt at any time (also persisted to
 * data/index/search-index.json as a cache). Search runs on a background
 * executor so the UI never blocks.
 */
public class SearchService {
    private static final Logger LOG = LoggerFactory.getLogger(SearchService.class);
    private static final String INDEX_FILE = "index/search-index.json";

    private final JsonStore store;
    private final TaskService taskService;
    private final NotesService notesService;
    private final FinanceService financeService;
    private final HabitService habitService;
    private final HealthService healthService;
    private final LearningService learningService;

    private final Map<String, SearchDoc> docs = new HashMap<>();
    private final Map<String, Set<String>> termIndex = new HashMap<>();

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
        rebuildTermIndex();
        persist();
        LOG.info("Search index rebuilt: {} docs in {} ms", docs.size(), System.currentTimeMillis() - start);
    }

    private void rebuildTermIndex() {
        termIndex.clear();
        for (SearchDoc doc : docs.values()) {
            for (String term : indexTerms(doc)) {
                termIndex.computeIfAbsent(term, k -> new HashSet<>()).add(doc.docId());
            }
        }
    }

    private List<String> indexTerms(SearchDoc doc) {
        Set<String> terms = new HashSet<>(TextSearch.terms(doc.title()));
        terms.addAll(TextSearch.terms(doc.tags()));
        terms.addAll(TextSearch.terms(doc.content()));
        return new ArrayList<>(terms);
    }

    private void indexTasks(Map<String, SearchDoc> out) {
        for (var t : taskService.all()) {
            String content = t.notes + " " + t.checklist.stream().map(c -> c.text).reduce("", (a, b) -> a + " " + b);
            out.put(docId(SearchDoc.TASK, t.id), new SearchDoc(docId(SearchDoc.TASK, t.id), SearchDoc.TASK,
                    t.title, content, String.join(" ", t.tags), t.id));
        }
    }

    private void indexNotes(Map<String, SearchDoc> out) {
        for (var n : notesService.list()) {
            String content = notesService.readContent(n.id);
            // Bound huge notes in the index; full-text search in NotesService remains exhaustive.
            if (content.length() > 20000) {
                content = content.substring(0, 20000);
            }
            out.put(docId(SearchDoc.NOTE, n.id), new SearchDoc(docId(SearchDoc.NOTE, n.id), SearchDoc.NOTE,
                    n.title, content, String.join(" ", n.tags), n.id));
        }
    }

    private void indexFinance(Map<String, SearchDoc> out) {
        for (var t : financeService.all()) {
            String categoryName = safeCall(() -> financeService.categoryById(t.categoryId).name);
            out.put(docId(SearchDoc.TRANSACTION, t.id), new SearchDoc(docId(SearchDoc.TRANSACTION, t.id),
                    SearchDoc.TRANSACTION, t.date + " " + t.type + " " + categoryName,
                    t.description + " " + t.amount.toPlainString(), String.join(" ", t.tags), t.id));
        }
    }

    private void indexHabits(Map<String, SearchDoc> out) {
        for (var h : habitService.habits(true)) {
            out.put(docId(SearchDoc.HABIT, h.id), new SearchDoc(docId(SearchDoc.HABIT, h.id), SearchDoc.HABIT,
                    h.name, h.description, "", h.id));
        }
        for (var g : habitService.goals()) {
            out.put(docId(SearchDoc.GOAL, g.id), new SearchDoc(docId(SearchDoc.GOAL, g.id), SearchDoc.GOAL,
                    g.title, g.description, "", g.id));
        }
    }

    private void indexHealth(Map<String, SearchDoc> out) {
        for (var e : healthService.entries()) {
            String content = "ngủ " + e.sleepHours + " tập " + e.exerciseMinutes + " cân " + e.weightKg + " " + e.note;
            out.put(docId(SearchDoc.HEALTH, e.id), new SearchDoc(docId(SearchDoc.HEALTH, e.id), SearchDoc.HEALTH,
                    "Sức khỏe " + e.date, content, "", e.id));
        }
    }

    private void indexLearning(Map<String, SearchDoc> out) {
        for (var c : learningService.courses()) {
            out.put(docId(SearchDoc.COURSE, c.id), new SearchDoc(docId(SearchDoc.COURSE, c.id), SearchDoc.COURSE,
                    c.title, c.source + " " + c.link, String.join(" ", c.tags), c.id));
        }
        for (var deck : learningService.decks()) {
            for (var card : learningService.cardsInDeck(deck.id)) {
                out.put(docId(SearchDoc.FLASHCARD, card.id), new SearchDoc(docId(SearchDoc.FLASHCARD, card.id),
                        SearchDoc.FLASHCARD, card.front, card.back, "", card.id));
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
        for (SearchDoc doc : docs.values()) {
            if (typeFilter != null && !typeFilter.isEmpty() && !typeFilter.contains(doc.type())) {
                continue;
            }
            String title = TextSearch.normalize(doc.title());
            String tags = TextSearch.normalize(doc.tags());
            String content = TextSearch.normalize(doc.content());
            int score = 0;
            boolean all = !terms.isEmpty();
            for (String term : terms) {
                int s = 0;
                if (title.contains(term)) {
                    s += 3;
                }
                if (tags.contains(term)) {
                    s += 2;
                }
                if (content.contains(term)) {
                    s += 1;
                }
                if (s == 0) {
                    all = false;
                    break;
                }
                score += s;
            }
            if (all) {
                scores.put(doc.docId(), score);
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

    public synchronized int docCount() {
        return docs.size();
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
        rebuildTermIndex();
        LOG.info("Loaded persisted search index: {} docs", docs.size());
    }

    /** All distinct doc types currently in the index. */
    public synchronized Set<String> types() {
        return new HashSet<>(docs.values().stream().map(SearchDoc::type).toList());
    }

    /** Simple stats for the dashboard. */
    public synchronized Map<String, Integer> countByType() {
        Map<String, Integer> out = new TreeMap<>();
        for (SearchDoc d : docs.values()) {
            out.merge(d.type(), 1, Integer::sum);
        }
        return out;
    }
}
