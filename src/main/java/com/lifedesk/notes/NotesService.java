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
    /** Explicit folder list so folders can exist while empty (like a vault tree). */
    private static final String FOLDERS_FILE = "notes/folders.json";
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
    private final List<String> storedFolders = new ArrayList<>();

    public NotesService(JsonStore store) {
        this.store = store;
        loadIndex();
        loadFolders();
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

    private void loadFolders() {
        JsonStore.StoredDoc doc = store.read(FOLDERS_FILE, SchemaMigrator.none(1));
        storedFolders.clear();
        if (doc != null && doc.data().has("folders") && doc.data().get("folders").isJsonArray()) {
            Type listType = new TypeToken<List<String>>() {
            }.getType();
            List<String> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray("folders"), listType);
            if (parsed != null) {
                parsed.stream().filter(f -> f != null && !f.isBlank()).forEach(storedFolders::add);
            }
        }
    }

    private void persistFolders() {
        JsonObject data = new JsonObject();
        data.add("folders", GsonFactory.create().toJsonTree(storedFolders));
        store.write(FOLDERS_FILE, 1, data);
    }

    /** Every known folder path, including parents of nested paths, sorted. */
    public synchronized List<String> listFolders() {
        java.util.TreeSet<String> all = new java.util.TreeSet<>(storedFolders);
        for (NoteMeta n : notes) {
            if (n.folder != null && !n.folder.isBlank()) {
                all.add(n.folder);
                for (String parent : ancestors(n.folder)) {
                    all.add(parent);
                }
            }
        }
        return new ArrayList<>(all);
    }

    private static List<String> ancestors(String path) {
        List<String> out = new ArrayList<>();
        int idx = path.lastIndexOf('/');
        while (idx > 0) {
            String parent = path.substring(0, idx);
            out.add(parent);
            idx = parent.lastIndexOf('/');
        }
        return out;
    }

    /** Normalises "a" / "/a/b/" and rejects traversal or empty segments. */
    private static String normalizeFolder(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/').replaceAll("^/+|/+$", "").trim();
        if (p.isEmpty()) {
            return "";
        }
        if (p.contains("..") || p.contains("//")) {
            throw new ValidationException("Tên thư mục không hợp lệ");
        }
        for (String segment : p.split("/")) {
            if (segment.isBlank()) {
                throw new ValidationException("Tên thư mục không hợp lệ");
            }
        }
        return p;
    }

    public synchronized String createFolder(String path) {
        String p = normalizeFolder(path);
        if (p.isEmpty()) {
            throw new ValidationException("Tên thư mục không được để trống");
        }
        if (!storedFolders.contains(p)) {
            storedFolders.add(p);
            storedFolders.sort(String::compareTo);
            persistFolders();
            change.fire();
        }
        return p;
    }

    /** Renames a folder and re-points every note (and subfolder) inside it. */
    public synchronized String renameFolder(String path, String newName) {
        String oldPath = normalizeFolder(path);
        String name = Validators.requireNonBlank(newName, "Tên thư mục").trim();
        if (name.contains("/") || name.contains("..")) {
            throw new ValidationException("Tên thư mục không được chứa '/' hoặc '..'");
        }
        int cut = oldPath.lastIndexOf('/');
        String parent = cut < 0 ? "" : oldPath.substring(0, cut);
        String newPath = parent.isEmpty() ? name : parent + "/" + name;
        if (newPath.equals(oldPath)) {
            return oldPath;
        }
        for (NoteMeta meta : new ArrayList<>(notes)) {
            String folder = meta.folder == null ? "" : meta.folder;
            if (folder.equals(oldPath) || folder.startsWith(oldPath + "/")) {
                String target = newPath + folder.substring(oldPath.length());
                updateMeta(meta, null, target, null, meta.starred);
            }
        }
        List<String> renames = new ArrayList<>();
        for (String folder : new ArrayList<>(storedFolders)) {
            if (folder.equals(oldPath) || folder.startsWith(oldPath + "/")) {
                String target = newPath + folder.substring(oldPath.length());
                renames.add(target);
                storedFolders.remove(folder);
            }
        }
        storedFolders.addAll(renames);
        storedFolders.sort(String::compareTo);
        persistFolders();
        change.fire();
        return newPath;
    }

    /** Deletes a folder with everything inside it and returns how many notes went away. */
    public synchronized int deleteFolder(String path) {
        String target = normalizeFolder(path);
        if (target.isEmpty()) {
            throw new ValidationException("Không thể xoá thư mục gốc");
        }
        List<NoteMeta> doomed = notes.stream()
                .filter(n -> n.folder != null && (n.folder.equals(target) || n.folder.startsWith(target + "/")))
                .toList();
        for (NoteMeta meta : doomed) {
            delete(meta.id);
        }
        storedFolders.removeIf(f -> f.equals(target) || f.startsWith(target + "/"));
        persistFolders();
        change.fire();
        return doomed.size();
    }

    /** Number of notes at or below a folder path. */
    public synchronized int countNotesInFolder(String path) {
        String target = normalizeFolder(path);
        return (int) notes.stream()
                .filter(n -> n.folder != null && (n.folder.equals(target) || n.folder.startsWith(target + "/")))
                .count();
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
        return listFolders();
    }

    /** Resolve a [[wiki link]] target to a note id (by title, normalized). */
    public synchronized Optional<String> resolveLink(String targetTitle) {
        String want = TextSearch.normalize(targetTitle);
        return notes.stream()
                .filter(n -> TextSearch.normalize(n.title).equals(want))
                .map(n -> n.id)
                .findFirst();
    }

    // ------------------------------------------------------------------ cross-module links

    /** Attach a note to a record of another module (finance/habits/health/learning). */
    public synchronized void linkNote(String noteId, String module, String recordId) {
        NoteMeta n = get(noteId);
        n.links.computeIfAbsent(module, k -> new LinkedHashSet<>()).add(recordId);
        n.updatedAt = Instant.now();
        persistIndex();
        change.fire();
    }

    public synchronized void unlinkNote(String noteId, String module, String recordId) {
        NoteMeta n = get(noteId);
        java.util.Set<String> ids = n.links.get(module);
        if (ids != null) {
            ids.remove(recordId);
            if (ids.isEmpty()) {
                n.links.remove(module);
            }
            persistIndex();
            change.fire();
        }
    }

    /** Toggle: attach when absent, detach when present. Returns true after call == linked. */
    public synchronized boolean toggleLink(String noteId, String module, String recordId) {
        NoteMeta n = get(noteId);
        boolean linked = n.links.getOrDefault(module, java.util.Set.of()).contains(recordId);
        if (linked) {
            unlinkNote(noteId, module, recordId);
        } else {
            linkNote(noteId, module, recordId);
        }
        return !linked;
    }

    public synchronized boolean isLinked(String noteId, String module, String recordId) {
        NoteMeta n = notes.stream().filter(x -> x.id.equals(noteId)).findFirst().orElse(null);
        return n != null && n.links.getOrDefault(module, java.util.Set.of()).contains(recordId);
    }

    /** All notes linked to a given record (reverse lookup; note count is small). */
    public synchronized List<NoteMeta> notesLinkedTo(String module, String recordId) {
        return notes.stream()
                .filter(n -> n.links.getOrDefault(module, java.util.Set.of()).contains(recordId))
                .toList();
    }

    /** Human summary of a note's links, e.g. "Tài chính(2), Thói quen(1)". */
    public synchronized String linksSummary(String noteId) {
        NoteMeta n = get(noteId);
        if (n.links.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        n.links.forEach((module, ids) -> {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(moduleLabel(module)).append('(').append(ids.size()).append(')');
        });
        return sb.toString();
    }

    public static String moduleLabel(String module) {
        return switch (module) {
            case "finance" -> "Tài chính";
            case "habits" -> "Thói quen";
            case "goals" -> "Mục tiêu";
            case "health" -> "Sức khỏe";
            case "learning" -> "Học tập";
            default -> module;
        };
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
