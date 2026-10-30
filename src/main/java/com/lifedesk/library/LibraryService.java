package com.lifedesk.library;

import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;
import com.lifedesk.storage.JsonStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Personal library (obsidian-library essence): one record per title across
 * books/movies/series/games/music/courses with cover, rating, progress and
 * auto-logged completion dates. Feeds the cover-gallery view and the
 * "Up next" / "Soon" shelves.
 */
public class LibraryService {
    public enum Kind { BOOK, MOVIE, SERIES, GAME, MUSIC, COURSE }

    public enum Status { PLANNED, IN_PROGRESS, DONE, DROPPED }

    public static class Item {
        public String id;
        public Kind kind = Kind.BOOK;
        public Status status = Status.PLANNED;
        public String title = "";
        public String creator = "";
        public int year;
        /** User rating 0..10 (0 = unrated). */
        public int rating;
        public int progressCurrent;
        public int progressTotal;
        public String coverUrl = "";
        public String note = "";
        public Instant addedAt = Instant.now();
        /** Auto-logged when progress reaches total or status becomes DONE. */
        public LocalDate completedAt;

        public Item() {
            this.id = com.lifedesk.common.Ids.newId();
        }
    }

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();
    private final List<Item> items = new ArrayList<>();

    public LibraryService(JsonStore store) {
        this.store = store;
        items.addAll(store.readList("library/items.json", "items",
                new TypeToken<List<Item>>() {
                }.getType()));
    }

    public ChangeNotifier changes() {
        return change;
    }

    public List<Item> all() {
        return items.stream().sorted(Comparator.comparing((Item i) -> i.addedAt).reversed()).toList();
    }

    public List<Item> byStatus(Status status) {
        return all().stream().filter(i -> i.status == status).toList();
    }

    public Item add(String title, Kind kind, String creator, int year, String coverUrl) {
        Validators.requireNonBlank(title, "Tiêu đề");
        if (items.stream().anyMatch(i -> i.title.equalsIgnoreCase(title.trim()) && i.kind == kind)) {
            throw new ValidationException("Đã có \"" + title + "\" trong thư viện.");
        }
        Item item = new Item();
        item.title = title.trim();
        item.kind = kind == null ? Kind.BOOK : kind;
        item.creator = creator == null ? "" : creator.trim();
        item.year = year;
        item.coverUrl = coverUrl == null ? "" : coverUrl.trim();
        items.add(item);
        persist();
        change.fire();
        return item;
    }

    public void update(Item updated) {
        Validators.requireNonBlank(updated.title, "Tiêu đề");
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(updated.id)) {
                if (updated.status == Status.DONE && updated.completedAt == null) {
                    updated.completedAt = LocalDate.now(); // auto-log completion date
                }
                if (updated.progressTotal > 0 && updated.progressCurrent >= updated.progressTotal
                        && updated.status != Status.DONE) {
                    updated.status = Status.DONE;
                    updated.completedAt = LocalDate.now();
                }
                items.set(i, updated);
                persist();
                change.fire();
                return;
            }
        }
        throw new NotFoundException("Không tìm thấy mục: " + updated.id);
    }

    public void delete(String id) {
        if (!items.removeIf(i -> i.id.equals(id))) {
            throw new NotFoundException("Không tìm thấy mục: " + id);
        }
        persist();
        change.fire();
    }

    /** Advance progress by one unit (episode/chapter tick) and auto-complete. */
    public void tickProgress(String id) {
        Item item = items.stream().filter(i -> i.id.equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy mục: " + id));
        item.progressCurrent++;
        if (item.progressTotal > 0 && item.progressCurrent >= item.progressTotal) {
            item.status = Status.DONE;
            item.completedAt = LocalDate.now();
        } else if (item.status == Status.PLANNED) {
            item.status = Status.IN_PROGRESS;
        }
        persist();
        change.fire();
    }

    /** Simple stats for the header: counts per kind + average rating. */
    public record Stats(long total, long inProgress, long done, double avgRating) {
    }

    public Stats stats() {
        long done = items.stream().filter(i -> i.status == Status.DONE).count();
        long inProgress = items.stream().filter(i -> i.status == Status.IN_PROGRESS).count();
        double avg = items.stream().filter(i -> i.rating > 0)
                .mapToInt(i -> i.rating).average().orElse(0);
        return new Stats(items.size(), inProgress, done, avg);
    }

    private void persist() {
        store.writeList("library/items.json", "items", 1, items);
    }
}
