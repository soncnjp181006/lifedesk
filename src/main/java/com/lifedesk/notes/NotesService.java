package com.lifedesk.notes;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.Fingerprint;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.StorageException;
import com.lifedesk.common.TextSearch;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Notes & Knowledge service.
 *
 * Markdown content is stored as real .md files under data/notes (user-owned,
 * portable); metadata lives in notes/index.json. Autosave and crash safety
 * rely on JsonStore's atomic writes. Conflict detection: each note remembers
 * the hash of its content at the last successful save — if the file changed
 * on disk since then (edited outside the app), the external version is
 * preserved as a -conflict-*.md copy instead of being overwritten.
 */
public class NotesService {
    public static final String NOTES_DIR = "notes";
    private static final String INDEX_FILE = "notes/index.json";
    private static final Logger LOG = LoggerFactory.getLogger(NotesService.class);

    private static final SchemaMigrator MIGRATOR = new SchemaMigrator() {
        @Override
        public int currentVersion() {
            return 1;
        }

        @Override
        public JsonObject migrate(JsonObject data, int fromVersion) {
            return data;
        }
    };

    private final JsonStore store;
    private final List<NoteMeta> notes = new ArrayList<>();
    /** In-memory content cache keyed by note id (write-through on save). */
    private final Map<String, String> contentCache = new HashMap<>();
    /** Hash of the content the editor currently holds, keyed by note id. */
    private final Map<String, String> loadedHash = new HashMap<>();
    private final ChangeNotifier change = new ChangeNotifier();

    public NotesService(JsonStore store) {
        this.store = store;
        loadIndex();
    }

    public ChangeNotifier changes() {
        return change;
    }

    private void loadIndex() {
        JsonStore.StoredDoc doc = store.read(INDEX_FILE, MIGRATOR);
        notes.clear();
        if (doc != null && doc.data().has("notes")) {
            Type listType = new TypeToken<List<NoteMeta>>() {
            }.getType();
            List<NoteMeta> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray("notes"), listType);
            if (parsed != null) {
                notes.addAll(parsed);
            }
        }
        // Content cache is populated lazily on first read.
    }

    private void persistIndex() {
        JsonObject data = new JsonObject();
        data.add("notes", GsonFactory.create().toJsonTree(notes));
        store.write(INDEX_FILE, 1, data);
    }

    public synchronized List<NoteMeta> list() {
        return notes.stream()
                .sorted(Comparator.comparing((NoteMeta n) -> n.updatedAt).reversed())
                .toList();
    }

    public synchronized NoteMeta get(String id) {
        return notes.stream().filter(n -> n.id.equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy ghi chú: " + id));
    }

    public synchronized Optional<NoteMeta> findByTitle(String title) {
        return notes.stream().filter(n -> n.title.equalsIgnoreCase(title)).findFirst();
    }

    public synchronized NoteMeta create(String title, String folder, String content, Set<String> tags) {
        title = Validators.requireNonBlank(title, "Tiêu đề ghi chú");
        if (folder != null) {
            folder = folder.replace('\\', '/').replaceAll("^/+|/+$", "");
            if (folder.contains("..")) {
                throw new ValidationException("Tên thư mục không hợp lệ");
            }
        }
        NoteMeta meta = new NoteMeta();
        meta.title = title.trim();
        meta.folder = folder == null ? "" : folder;
        if (tags != null) {
            meta.tags.addAll(tags);
        }
        meta.contentFile = fileNameFor(meta);
        String body = content == null ? "" : content;
        meta.lastSavedHash = Fingerprint.sha256(body);
        store.writeText(meta.contentFile, body);
        notes.add(meta);
        persistIndex();
        contentCache.put(meta.id, body);
        loadedHash.put(meta.id, meta.lastSavedHash);
        change.fire();
        return meta;
    }

    public synchronized String readContent(String id) {
        if (contentCache.containsKey(id)) {
            return contentCache.get(id);
        }
        NoteMeta meta = get(id);
        String content = store.readText(meta.contentFile);
        if (content == null) {
            // Content file missing (deleted externally): start from an empty doc, never crash.
            content = "";
        }
        contentCache.put(id, content);
        loadedHash.put(id, Fingerprint.sha256(content));
        return content;
    }

    /**
     * Save note content with conflict detection.
     *
     * @param id       note id
     * @param content  new content
     * @param editorHash hash of the content the editor loaded/holds (from {@link #readContent} plus local edits),
     *                   or null to skip conflict bookkeeping
     */
    public synchronized SaveResult saveContent(String id, String content, String editorHash) {
        NoteMeta meta = get(id);
        if (editorHash != null) {
            loadedHash.put(id, editorHash);
        }
        String currentFileHash;
        try {
            String current = store.readText(meta.contentFile);
            currentFileHash = current == null ? "" : Fingerprint.sha256(current);
        } catch (StorageException e) {
            currentFileHash = "<unreadable>";
        }

        String lastSaved = meta.lastSavedHash == null ? "" : meta.lastSavedHash;
        SaveResult result = SaveResult.SAVED;
        if (!currentFileHash.equals(lastSaved) && !lastSaved.isEmpty()) {
            // The file changed outside this app since our last save.
            if (currentFileHash.length() == 64) {
                String conflictFile = conflictFileName(meta);
                try {
                    String externalContent = store.readText(meta.contentFile);
                    store.writeText(conflictFile, externalContent == null ? "" : externalContent);
                    LOG.warn("External modification detected for note {}; preserved as {}", meta.title, conflictFile);
                    result = SaveResult.SAVED_WITH_CONFLICT_COPY;
                } catch (StorageException ex) {
                    LOG.error("Could not preserve conflicting note content for {}", meta.title, ex);
                }
            }
        }

        String newHash = Fingerprint.sha256(content);
        if (newHash.equals(lastSaved) && result == SaveResult.SAVED) {
            return SaveResult.UNCHANGED;
        }
        store.writeText(meta.contentFile, content);
        meta.lastSavedHash = newHash;
        meta.updatedAt = Instant.now();
        // Refresh tags from content + metadata.
        Set<String> merged = new LinkedHashSet<>(meta.tags);
        merged.addAll(NoteLinkParser.tags(content));
        meta.tags = merged;
        persistIndex();
        contentCache.put(id, content);
        loadedHash.put(id, newHash);
        change.fire();
        return result;
    }

    public synchronized void delete(String id) {
        NoteMeta meta = get(id);
        store.delete(meta.contentFile);
        notes.remove(meta);
        contentCache.remove(id);
        loadedHash.remove(id);
        persistIndex();
        change.fire();
    }

    public synchronized void updateMeta(NoteMeta meta, String newTitle, String newFolder, Set<String> tags, boolean starred) {
        if (newTitle != null && !newTitle.isBlank()) {
            if (!meta.title.equals(newTitle.trim())) {
                meta.title = newTitle.trim();
                // Rename content file to follow the new title; id stays stable.
                String oldFile = meta.contentFile;
                String newFile = fileNameFor(meta);
                if (!oldFile.equals(newFile)) {
                    String content = store.readText(oldFile);
                    if (content != null) {
                        store.writeText(newFile, content);
                        store.delete(oldFile);
                    }
                    meta.contentFile = newFile;
                }
            }
        }
        if (newFolder != null) {
            newFolder = newFolder.replace('\\', '/').replaceAll("^/+|/+$", "");
            if (!meta.folder.equals(newFolder)) {
                String content = store.readText(meta.contentFile);
                String newFile = newFolder.isEmpty()
                        ? fileNameFor(meta.title, "", meta.id)
                        : fileNameFor(meta.title, newFolder, meta.id);
                if (content != null) {
                    store.writeText(newFile, content);
                    store.delete(meta.contentFile);
                }
                meta.folder = newFolder;
                meta.contentFile = newFile;
            }
        }
        if (tags != null) {
            meta.tags = new LinkedHashSet<>(tags);
        }
        meta.starred = starred;
        meta.updatedAt = Instant.now();
        persistIndex();
        change.fire();
    }

    public synchronized List<NoteMeta> search(String query) {
        List<NoteMeta> out = new ArrayList<>();
        for (NoteMeta n : notes) {
            if (query == null || query.isBlank()) {
                out.add(n);
                continue;
            }
            String hay = n.title + " " + n.folder + " " + String.join(" ", n.tags) + " " + readContent(n.id);
            if (TextSearch.matches(hay, query)) {
                out.add(n);
            }
        }
        return out;
    }

    public synchronized List<String> folders() {
        return notes.stream().map(n -> n.folder).distinct().sorted().toList();
    }

    /** Resolve a [[wiki link]] target to a note id (by title, normalized). */
    public synchronized Optional<String> resolveLink(String targetTitle) {
        String want = TextSearch.normalize(targetTitle);
        return notes.stream()
                .filter(n -> TextSearch.normalize(n.title).equals(want))
                .map(n -> n.id)
                .findFirst();
    }

    public Path notesRoot() {
        return store.resolve(NOTES_DIR);
    }

    private String fileNameFor(NoteMeta meta) {
        return fileNameFor(meta.title, meta.folder, meta.id);
    }

    private String fileNameFor(String title, String folder, String id) {
        String safe = title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safe.isBlank()) {
            safe = "note";
        }
        String name = safe + "-" + id.substring(0, 8) + ".md";
        return folder == null || folder.isBlank()
                ? NOTES_DIR + "/" + name
                : NOTES_DIR + "/" + folder + "/" + name;
    }

    private String conflictFileName(NoteMeta meta) {
        String base = meta.contentFile.substring(0, meta.contentFile.length() - 3);
        return base + "-conflict-" + System.currentTimeMillis() + ".md";
    }

    public int count() {
        return notes.size();
    }
}
