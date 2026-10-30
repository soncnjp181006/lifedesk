package com.lifedesk.rss;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.ValidationException;
import com.lifedesk.storage.JsonStore;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLConnection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RSS/Atom dashboard backend (obsidian-rss-dashboard essence): subscriptions
 * with folders, fetched article lists (in memory), read state + saved
 * articles persisted locally, OPML import/export. Fetches happen only on
 * explicit refresh — the app never polls on its own.
 */
public class RssService {
    private static final Logger LOG = LoggerFactory.getLogger(RssService.class);
    private static final int MAX_ITEMS_PER_FEED = 50;

    /** One subscription. */
    public static class Feed {
        public String id;
        public String title = "";
        public String url = "";
        public String folder = "";

        public Feed() {
            this.id = com.lifedesk.common.Ids.newId();
        }
    }

    /** One fetched article (not persisted — re-fetched on refresh). */
    public record Article(String feedId, String feedTitle, String title, String url,
                          Instant published, String summary) {
    }

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();
    private final List<Feed> feeds = new ArrayList<>();
    /** url → read flag (persisted; survives re-fetches). */
    private final Set<String> readUrls = new HashSet<>();
    /** url → saved flag (persisted). */
    private final Set<String> savedUrls = new HashSet<>();
    private volatile List<Article> articles = List.of();

    public RssService(JsonStore store) {
        this.store = store;
        feeds.addAll(store.readList("rss/feeds.json", "feeds",
                new TypeToken<List<Feed>>() {
                }.getType()));
        readUrls.addAll(store.readList("rss/read.json", "read",
                new TypeToken<List<String>>() {
                }.getType()));
        savedUrls.addAll(store.readList("rss/saved.json", "saved",
                new TypeToken<List<String>>() {
                }.getType()));
    }

    public ChangeNotifier changes() {
        return change;
    }

    public List<Feed> feeds() {
        return List.copyOf(feeds);
    }

    public Feed addFeed(String url, String folder) {
        String trimmed = com.lifedesk.common.Validators.requireNonBlank(url, "URL feed");
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new ValidationException("Feed phải là URL http(s): " + trimmed);
        }
        Feed f = new Feed();
        f.url = trimmed.trim();
        f.folder = folder == null ? "" : folder.trim();
        f.title = f.url; // replaced with the real feed title on first refresh
        feeds.add(f);
        persistFeeds();
        change.fire();
        return f;
    }

    public void removeFeed(String feedId) {
        feeds.removeIf(f -> f.id.equals(feedId));
        persistFeeds();
        change.fire();
    }

    public List<Article> articles() {
        return articles;
    }

    public boolean isRead(String url) {
        return readUrls.contains(url);
    }

    public void markRead(String url, boolean read) {
        if (read) {
            readUrls.add(url);
        } else {
            readUrls.remove(url);
        }
        persistRead();
        change.fire();
    }

    public void markAllRead() {
        for (Article a : articles) {
            readUrls.add(a.url());
        }
        persistRead();
        change.fire();
    }

    public boolean isSaved(String url) {
        return savedUrls.contains(url);
    }

    public void markSaved(String url) {
        savedUrls.add(url);
        persistSaved();
        change.fire();
    }

    /**
     * Fetch every feed (background thread). One broken feed is reported and
     * skipped — a single dead site must not break the whole dashboard.
     */
    public Map<String, String> refreshAll() {
        List<Article> fresh = new ArrayList<>();
        Map<String, String> errors = new HashMap<>();
        for (Feed f : feeds) {
            try {
                SyndFeed parsed = fetch(f.url);
                if (f.title == null || f.title.isBlank() || f.title.equals(f.url)) {
                    f.title = parsed.getTitle() == null ? f.url : parsed.getTitle();
                }
                int count = 0;
                for (SyndEntry entry : parsed.getEntries()) {
                    if (count >= MAX_ITEMS_PER_FEED) {
                        break;
                    }
                    String url = entry.getLink() == null ? "" : entry.getLink();
                    String summary = entry.getDescription() == null ? "" : entry.getDescription().getValue();
                    fresh.add(new Article(f.id, f.title, entry.getTitle() == null ? "(không tiêu đề)" : entry.getTitle(),
                            url, entry.getPublishedDate() == null ? Instant.now() : entry.getPublishedDate().toInstant(),
                            summary == null ? "" : summary));
                    count++;
                }
            } catch (Exception e) {
                errors.put(f.title, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }
        fresh.sort((a, b) -> b.published().compareTo(a.published()));
        articles = List.copyOf(fresh);
        persistFeeds(); // titles may have been filled in
        change.fire();
        return errors;
    }

    private SyndFeed fetch(String url) throws Exception {
        URLConnection conn = URI.create(url).toURL().openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "LifeDesk/1.0 (RSS reader)");
        try (var in = conn.getInputStream()) {
            return new SyndFeedInput().build(new XmlReader(in));
        }
    }

    // ------------------------------------------------------------------ OPML

    /** Export subscriptions as OPML 2.0 (folder = outline group). */
    public String exportOpml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n<head><title>LifeDesk feeds</title></head>\n<body>\n");
        Map<String, List<Feed>> byFolder = new HashMap<>();
        for (Feed f : feeds) {
            byFolder.computeIfAbsent(f.folder.isEmpty() ? "(gốc)" : f.folder, k -> new ArrayList<>()).add(f);
        }
        for (var e : byFolder.entrySet()) {
            boolean grouped = !e.getKey().equals("(gốc)");
            if (grouped) {
                sb.append("  <outline text=\"").append(escape(e.getKey())).append("\">\n");
            }
            for (Feed f : e.getValue()) {
                sb.append(grouped ? "    " : "  ")
                        .append("<outline type=\"rss\" text=\"").append(escape(f.title))
                        .append("\" xmlUrl=\"").append(escape(f.url)).append("\"/>\n");
            }
            if (grouped) {
                sb.append("  </outline>\n");
            }
        }
        sb.append("</body>\n</opml>\n");
        return sb.toString();
    }

    /** Import OPML: adds every rss outline found (existing URLs skipped). */
    public int importOpml(String opmlXml) {
        int added = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<outline[^>]*type=\"rss\"[^>]*xmlUrl=\"([^\"]+)\"[^>]*>").matcher(opmlXml);
        Set<String> existing = new HashSet<>(feeds.stream().map(f -> f.url).toList());
        while (m.find()) {
            String url = m.group(1);
            if (existing.contains(url)) {
                continue;
            }
            Feed f = new Feed();
            f.url = url;
            java.util.regex.Matcher t = java.util.regex.Pattern.compile("text=\"([^\"]*)\"")
                    .matcher(m.group());
            if (t.find()) {
                f.title = t.group(1);
            }
            feeds.add(f);
            existing.add(url);
            added++;
        }
        if (added > 0) {
            persistFeeds();
            change.fire();
        }
        return added;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void persistFeeds() {
        store.writeList("rss/feeds.json", "feeds", 1, feeds);
    }

    private void persistRead() {
        store.writeList("rss/read.json", "read", 1, new ArrayList<>(readUrls));
    }

    private void persistSaved() {
        store.writeList("rss/saved.json", "saved", 1, new ArrayList<>(savedUrls));
    }
}
