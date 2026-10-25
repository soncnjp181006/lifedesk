package com.lifedesk.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.lifedesk.common.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-based persistence core.
 *
 * Every payload is stored as an envelope: {@code {"schemaVersion":N,"data":{...}}}.
 * Writes are atomic: the payload goes to a temp file which is flushed and
 * fsynced, then moved over the target. The previous target is preserved as
 * {@code <file>.bak} before replacement. Corrupt reads fall back to the
 * {@code .bak} copy; if that also fails the corrupt file is quarantined as
 * {@code <file>.corrupt-<timestamp>} and the problem is reported to the
 * {@link StorageIssueLog} — never silently swallowed.
 *
 * In-JVM concurrent writes to the same path are serialized via per-path locks.
 * Cross-instance concurrency is prevented by a data-directory lock file
 * (see {@link com.lifedesk.app.SingleInstanceLock}).
 */
public class JsonStore {
    private static final Logger LOG = LoggerFactory.getLogger(JsonStore.class);
    private static final String ENVELOPE_VERSION = "schemaVersion";
    private static final String ENVELOPE_DATA = "data";

    private final Path root;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final StorageIssueLog issueLog;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public JsonStore(Path root, StorageIssueLog issueLog) {
        this.root = root.toAbsolutePath().normalize();
        this.issueLog = issueLog;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageException("Không thể tạo thư mục dữ liệu: " + this.root, e);
        }
    }

    public Path root() {
        return root;
    }

    /** Absolute path for a store-relative path. Rejects escaping the data root. */
    public Path resolve(String relativePath) {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw new StorageException("Đường dẫn dữ liệu không hợp lệ: " + relativePath);
        }
        return p;
    }

    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /**
     * Read a versioned document. Returns null when the file does not exist
     * (caller decides the default). Applies {@code migrator} when the stored
     * version is older than the current one. Falls back to the .bak copy on
     * corruption and quarantines unreadable files.
     */
    public StoredDoc read(String relativePath, SchemaMigrator migrator) {
        Path target = resolve(relativePath);
        ReentrantLock lock = lockFor(relativePath);
        lock.lock();
        try {
            if (!Files.exists(target)) {
                return null;
            }
            StoredDoc doc = tryParse(target, migrator);
            if (doc != null) {
                return doc;
            }
            // Corrupt primary: try the .bak copy.
            Path bak = bakPath(target);
            if (Files.exists(bak)) {
                doc = tryParse(bak, migrator);
                if (doc != null) {
                    issueLog.warning(relativePath, "File chính bị hỏng, đã phục hồi từ bản sao lưu .bak");
                    LOG.warn("Restored {} from .bak after corrupt read", relativePath);
                    return doc;
                }
            }
            quarantine(target);
            issueLog.error(relativePath, "File dữ liệu hỏng và không có bản sao lưu hợp lệ; đã chuyển vào tập tin .corrupt-*");
            return null;
        } finally {
            lock.unlock();
        }
    }

    /** Parse one file into a StoredDoc; null when unreadable. */
    private StoredDoc tryParse(Path file, SchemaMigrator migrator) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject envelope = JsonParser.parseReader(reader).getAsJsonObject();
            int version = envelope.has(ENVELOPE_VERSION) ? envelope.get(ENVELOPE_VERSION).getAsInt() : 1;
            JsonObject data = envelope.has(ENVELOPE_DATA) && envelope.get(ENVELOPE_DATA).isJsonObject()
                    ? envelope.getAsJsonObject(ENVELOPE_DATA)
                    : new JsonObject();
            if (version < migrator.currentVersion()) {
                data = migrator.migrate(data, version);
            }
            return new StoredDoc(migrator.currentVersion(), data);
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            LOG.warn("Cannot parse {}: {}", file, e.toString());
            return null;
        }
    }

    private void quarantine(Path file) {
        try {
            Path quarantine = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
            Files.move(file, quarantine, StandardCopyOption.REPLACE_EXISTING);
            LOG.warn("Quarantined corrupt file to {}", quarantine);
        } catch (IOException e) {
            LOG.error("Failed to quarantine corrupt file {}", file, e);
        }
    }

    /**
     * Atomically write a versioned document. Any failure throws
     * {@link StorageException}; callers must surface it to the user.
     */
    public void write(String relativePath, int schemaVersion, JsonObject data) {
        Path target = resolve(relativePath);
        ReentrantLock lock = lockFor(relativePath);
        lock.lock();
        try {
            JsonObject envelope = new JsonObject();
            envelope.addProperty(ENVELOPE_VERSION, schemaVersion);
            envelope.add(ENVELOPE_DATA, data);

            String json = gson.toJson(envelope);
            Path tmp = null;
            try {
                Files.createDirectories(target.getParent());
                tmp = target.resolveSibling(target.getFileName() + ".tmp-" + IdFactory.next());
                // Write + fsync temp file.
                try (FileChannel ch = FileChannel.open(tmp,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ch.write(StandardCharsets.UTF_8.encode(json));
                    ch.force(true);
                }
                // Preserve previous content as .bak (best effort).
                if (Files.exists(target)) {
                    Path bak = bakPath(target);
                    try {
                        Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        LOG.warn("Could not refresh .bak for {}: {}", target, e.toString());
                    }
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                // Success: promote the new content to .bak so a later corruption
                // recovers the newest known-good version (crash mid-write keeps
                // the previous version in .bak, which is the safe WAL behavior).
                try {
                    Files.copy(target, bakPath(target), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("Could not refresh .bak after write for {}: {}", target, e.toString());
                }
            } catch (IOException e) {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignore) {
                        // tmp cleanup is best effort; the real failure is reported below.
                    }
                }
                String msg = "Ghi dữ liệu thất bại (" + relativePath + "): " + e.getMessage();
                issueLog.error(relativePath, msg);
                throw new StorageException(msg, e);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Read a small file as raw UTF-8 text (e.g. Markdown notes). Null when absent. */
    public String readText(String relativePath) {
        Path p = resolve(relativePath);
        if (!Files.exists(p)) {
            return null;
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            issueLog.error(relativePath, "Không đọc được file: " + e.getMessage());
            throw new StorageException("Không đọc được file " + relativePath, e);
        }
    }

    /** Atomically write raw UTF-8 text (Markdown notes), keeping a .bak copy. */
    public void writeText(String relativePath, String content) {
        Path target = resolve(relativePath);
        ReentrantLock lock = lockFor(relativePath);
        lock.lock();
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + IdFactory.next());
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ch.write(StandardCharsets.UTF_8.encode(content));
                ch.force(true);
            }
            if (Files.exists(target)) {
                try {
                    Files.copy(target, bakPath(target), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("Could not refresh .bak for {}: {}", target, e.toString());
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            String msg = "Ghi file thất bại (" + relativePath + "): " + e.getMessage();
            issueLog.error(relativePath, msg);
            throw new StorageException(msg, e);
        } finally {
            lock.unlock();
        }
    }

    public void delete(String relativePath) {
        Path p = resolve(relativePath);
        try {
            Files.deleteIfExists(p);
            Files.deleteIfExists(bakPath(p));
        } catch (IOException e) {
            issueLog.error(relativePath, "Không xóa được file: " + e.getMessage());
            throw new StorageException("Không xóa được file " + relativePath, e);
        }
    }

    private Path bakPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".bak");
    }

    private ReentrantLock lockFor(String relativePath) {
        return locks.computeIfAbsent(relativePath, k -> new ReentrantLock());
    }

    /** Envelope parsed by {@link #read}. */
    public record StoredDoc(int schemaVersion, JsonObject data) {
    }

    /** Small collision-free suffix for temp files. */
    static final class IdFactory {
        private static long counter = 0;

        static synchronized String next() {
            counter++;
            return Long.toString(System.currentTimeMillis(), 36) + "-" + Long.toString(counter, 36);
        }
    }

    /** Utility used by backup to enumerate data files relative to root. */
    public void walkDataFiles(java.util.function.Consumer<Path> consumer) throws IOException {
        Files.walk(root)
                .filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                .filter(p -> !p.getFileName().toString().endsWith(".lock"))
                .forEach(consumer);
    }
}
