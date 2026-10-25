package com.lifedesk.files;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.TextSearch;
import com.lifedesk.security.PathGuard;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File manager over user-granted root folders only.
 *
 * Hard rules enforced here:
 * - nothing may be deleted, renamed or moved (no such operation exists);
 * - no full-disk scan: every access is rooted in an allowed root;
 * - opening a file uses the OS default application only.
 */
public class FileManagerService {
    private static final Logger LOG = LoggerFactory.getLogger(FileManagerService.class);

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();
    private final List<FileFavorite> favorites = new ArrayList<>();
    private volatile List<Path> allowedRoots = List.of();

    public FileManagerService(JsonStore store, List<String> allowedRootPaths) {
        this.store = store;
        setAllowedRoots(allowedRootPaths);
        Type listType = new TypeToken<List<FileFavorite>>() {
        }.getType();
        JsonStore.StoredDoc doc = store.read("files/favorites.json", SchemaMigrator.none(1));
        if (doc != null && doc.data().has("favorites")) {
            List<FileFavorite> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray("favorites"), listType);
            if (parsed != null) {
                favorites.addAll(parsed);
            }
        }
    }

    public ChangeNotifier changes() {
        return change;
    }

    public void setAllowedRoots(List<String> paths) {
        List<Path> roots = new ArrayList<>();
        for (String p : paths) {
            if (p == null || p.isBlank()) {
                continue;
            }
            roots.add(Paths.get(p).toAbsolutePath().normalize());
        }
        this.allowedRoots = List.copyOf(roots);
    }

    public List<Path> allowedRoots() {
        return allowedRoots;
    }

    public boolean isAllowed(Path candidate) {
        return PathGuard.isInside(allowedRoots, candidate);
    }

    public void requireAllowed(Path candidate) {
        PathGuard.requireInside(allowedRoots, candidate);
    }

    /** Direct children of a directory inside an allowed root. */
    public List<FileHit> list(Path dir) {
        requireAllowed(dir);
        List<FileHit> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.forEach(p -> out.add(toHit(p)));
        } catch (IOException e) {
            throw new com.lifedesk.common.AppException("Không đọc được thư mục: " + e.getMessage(), e);
        }
        out.sort((a, b) -> {
            if (a.directory() != b.directory()) {
                return a.directory() ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        return out;
    }

    /**
     * Recursive search inside an allowed root. Runs on the caller's thread
     * (UI calls this from a background task) and is bounded by maxDepth and
     * maxResults so it can never run away.
     */
    public List<FileHit> search(FileSearchOptions options) {
        requireAllowed(options.root);
        List<FileHit> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(options.root, Math.max(0, options.maxDepth))) {
            stream.filter(Files::isReadable).forEach(p -> {
                if (out.size() >= options.maxResults) {
                    return;
                }
                if (p.equals(options.root)) {
                    return;
                }
                FileHit hit = toHit(p);
                if (matches(hit, options)) {
                    out.add(hit);
                }
            });
        } catch (IOException e) {
            LOG.warn("File search interrupted: {}", e.toString());
        }
        return out;
    }

    private boolean matches(FileHit hit, FileSearchOptions o) {
        if (!o.includeSubfolders && !hit.path().getParent().equals(o.root)) {
            return false;
        }
        if (o.nameQuery != null && !o.nameQuery.isBlank()
                && !TextSearch.matches(hit.name(), o.nameQuery)) {
            return false;
        }
        if (!o.extensions.isEmpty() && hit.directory()) {
            return false;
        }
        if (!o.extensions.isEmpty() && (hit.extension() == null
                || !o.extensions.contains(hit.extension().toLowerCase()))) {
            return false;
        }
        if (o.minSizeBytes != null && hit.sizeBytes() < o.minSizeBytes) {
            return false;
        }
        if (o.maxSizeBytes != null && hit.sizeBytes() > o.maxSizeBytes) {
            return false;
        }
        if (o.modifiedAfter != null && hit.lastModified().isBefore(o.modifiedAfter)) {
            return false;
        }
        if (o.modifiedBefore != null && hit.lastModified().isAfter(o.modifiedBefore)) {
            return false;
        }
        return true;
    }

    private FileHit toHit(Path p) {
        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        boolean dir = Files.isDirectory(p);
        long size = 0;
        Instant modified = Instant.EPOCH;
        String ext = null;
        try {
            BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
            size = attrs.size();
            modified = attrs.lastModifiedTime().toInstant();
            int dot = name.lastIndexOf('.');
            if (!dir && dot > 0 && dot < name.length() - 1) {
                ext = name.substring(dot + 1).toLowerCase();
            }
        } catch (IOException e) {
            // keep defaults; file may have vanished mid-scan
        }
        return new FileHit(p, name, dir, size, modified, ext);
    }

    /** Open a file with the OS default application (user-initiated only). */
    public boolean openWithDefaultApp(Path file) {
        requireAllowed(file);
        if (!Files.isRegularFile(file)) {
            throw new com.lifedesk.common.AppException("Không phải file thông thường: " + file);
        }
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                return false;
            }
            Desktop.getDesktop().open(file.toFile());
            return true;
        } catch (IOException e) {
            throw new com.lifedesk.common.AppException("Không mở được file: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ favorites

    public List<FileFavorite> favorites() {
        return List.copyOf(favorites);
    }

    public void addFavorite(String path, String label) {
        requireAllowed(Paths.get(path));
        boolean exists = favorites.stream().anyMatch(f -> f.path.equals(path));
        if (exists) {
            return;
        }
        favorites.add(new FileFavorite(path, label == null ? "" : label));
        persistFavorites();
        change.fire();
    }

    public void removeFavorite(String path) {
        favorites.removeIf(f -> f.path.equals(path));
        persistFavorites();
        change.fire();
    }

    private void persistFavorites() {
        JsonObject data = new JsonObject();
        data.add("favorites", GsonFactory.create().toJsonTree(favorites));
        store.write("files/favorites.json", 1, data);
    }
}
