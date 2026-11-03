package com.lifedesk.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lifedesk.app.AppContext;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.ValidationException;
import com.lifedesk.config.ConfigService;
import com.lifedesk.finance.TxType;
import com.lifedesk.habits.Habit;
import com.lifedesk.health.HealthService;
import com.lifedesk.health.HealthEntry;
import com.lifedesk.learning.Course;
import com.lifedesk.learning.Deck;
import com.lifedesk.learning.Flashcard;
import com.lifedesk.notes.NoteMeta;
import com.lifedesk.notes.NotesService;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.tasks.Priority;
import com.lifedesk.tasks.RecurrenceRule;
import com.lifedesk.tasks.RecurrenceType;
import com.lifedesk.tasks.Task;
import com.lifedesk.tasks.TaskStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local JSON API for the React UI. Binds to loopback only; the JavaFX WebView
 * loads the SPA from this server, so REST calls are same-origin (Tauri-like
 * webview + native backend, with HTTP as the bridge).
 */
public final class ApiServer {
    private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
    private final AppContext ctx;
    private final Gson gson = GsonFactory.create();
    /** Serializes module calls whose services are not internally synchronized. */
    private final Object moduleLock = new Object();
    private HttpServer server;
    private ExecutorService pool;
    private int port;

    public ApiServer(AppContext ctx) {
        this.ctx = ctx;
    }

    public synchronized int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        port = server.getAddress().getPort();
        pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "lifedesk-api");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
        LOG.info("Local API listening on http://127.0.0.1:{}", port);
        return port;
    }

    public int port() {
        return port;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                route(ex, path.substring(4));
            } else {
                serveStatic(ex, path);
            }
        } catch (NotFoundException e) {
            sendError(ex, 404, e.getMessage());
        } catch (ValidationException | IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage() == null ? "Yêu cầu không hợp lệ" : e.getMessage());
        } catch (Throwable t) {
            LOG.error("API {} {} failed", ex.getRequestMethod(), ex.getRequestURI(), t);
            sendError(ex, 500, t.getMessage() == null ? t.toString() : t.getMessage());
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex, String apiPath) throws Exception {
        String method = ex.getRequestMethod();
        List<String> p = Arrays.stream(apiPath.split("/"))
                .filter(s -> !s.isBlank()).toList();
        if (p.isEmpty()) {
            sendError(ex, 404, "API không tồn tại");
            return;
        }
        String head = p.get(0);
        if (method.equals("OPTIONS")) {
            sendJson(ex, 204, new JsonObject());
            return;
        }
        switch (head) {
            case "bootstrap" -> sendJson(ex, 200, bootstrap());
            case "dashboard" -> sendJson(ex, 200, dashboard());
            case "tasks" -> tasks(ex, method, p);
            case "notes" -> notes(ex, method, p);
            case "markdown" -> markdown(ex, method);
            case "finance" -> finance(ex, method, p);
            case "habits" -> habits(ex, method, p);
            case "health" -> health(ex, method, p);
            case "learning" -> learning(ex, method, p);
            case "ai" -> ai(ex, method, p);
            case "library" -> library(ex, method, p);
            case "notifications" -> notifications(ex, method, p);
            case "search" -> search(ex, method);
            case "config" -> config(ex, method);
            default -> sendError(ex, 404, "API không tồn tại: " + head);
        }
    }

    // ------------------------------------------------------------------ bootstrap / dashboard

    private JsonObject bootstrap() {
        JsonObject out = new JsonObject();
        JsonObject cfg = gson.toJsonTree(ctx.config()).getAsJsonObject();
        JsonObject ai = cfg.has("ai") ? cfg.getAsJsonObject("ai") : null;
        if (ai != null && ai.has("kilo")) {
            ai.getAsJsonObject("kilo").remove("apiKey");
        }
        out.add("config", cfg);
        out.addProperty("dataDir", ctx.dataDir().toString());
        out.addProperty("aiEnabled", ctx.ai().enabled());
        out.addProperty("activeProvider", ctx.config().ai.providerId);
        out.addProperty("activeModel", ctx.ai().activeModel());
        JsonArray providers = new JsonArray();
        for (var provider : ctx.ai().providers()) {
            JsonObject p = new JsonObject();
            p.addProperty("id", provider.id());
            p.addProperty("name", provider.displayName());
            providers.add(p);
        }
        out.add("providers", providers);
        return out;
    }

    private JsonObject dashboard() {
        LocalDate today = LocalDate.now();
        JsonObject out = new JsonObject();

        List<Task> tasks = ctx.tasks().all();
        JsonObject t = new JsonObject();
        t.addProperty("open", tasks.stream().filter(x -> x.status != TaskStatus.DONE
                && x.status != TaskStatus.CANCELLED).count());
        t.addProperty("done", tasks.stream().filter(x -> x.status == TaskStatus.DONE).count());
        t.addProperty("dueToday", tasks.stream().filter(x -> x.isDueToday(today)).count());
        t.addProperty("overdue", tasks.stream().filter(x -> x.isOverdue(today)).count());
        t.add("dueTodayList", taskList(tasks.stream().filter(x -> x.isDueToday(today)).toList(), today));
        t.add("overdueList", taskList(tasks.stream().filter(x -> x.isOverdue(today))
                .sorted(Comparator.comparing((Task x) -> x.dueDate)).limit(6).toList(), today));
        t.add("upcoming", taskList(tasks.stream()
                .filter(x -> x.dueDate != null && !x.dueDate.isBefore(today)
                        && !x.dueDate.isAfter(today.plusDays(7))
                        && x.status != TaskStatus.DONE && x.status != TaskStatus.CANCELLED)
                .sorted(Comparator.comparing((Task x) -> x.dueDate)).limit(8).toList(), today));
        out.add("tasks", t);

        JsonObject n = new JsonObject();
        n.addProperty("count", ctx.notes().count());
        n.add("recent", notesArray(ctx.notes().list().stream().limit(6).toList()));
        out.add("notes", n);

        YearMonth month = YearMonth.now();
        JsonObject f = new JsonObject();
        var summary = ctx.finance().monthSummary(month);
        f.add("summary", gson.toJsonTree(summary));
        f.add("budgets", gson.toJsonTree(ctx.finance().budgetStatus(month)));
        f.add("series", gson.toJsonTree(ctx.finance().dailySeries(today.minusDays(29), today)));
        out.add("finance", f);

        JsonArray habits = new JsonArray();
        for (Habit h : ctx.habits().habits(false)) {
            JsonObject item = gson.toJsonTree(h).getAsJsonObject();
            item.addProperty("completedToday", ctx.habits().isCompleted(h.id, today));
            item.addProperty("scheduledToday", h.scheduledOn(today.getDayOfWeek()));
            item.addProperty("streak", ctx.habits().currentStreak(h));
            habits.add(item);
        }
        out.add("habits", habits);

        JsonObject learning = new JsonObject();
        learning.addProperty("dueCards", ctx.learning().dueCount());
        JsonArray decks = new JsonArray();
        for (Deck d : ctx.learning().decks()) {
            JsonObject dj = gson.toJsonTree(d).getAsJsonObject();
            dj.addProperty("dueCount", ctx.learning().dueCountForDeck(d.id));
            decks.add(dj);
        }
        learning.add("decks", decks);
        learning.addProperty("courses", ctx.learning().courses().size());
        out.add("learning", learning);

        JsonObject health = new JsonObject();
        health.addProperty("avgSleep7", ctx.health().averageDays(HealthService.HealthField.SLEEP, 7));
        health.addProperty("avgExercise7", ctx.health().averageDays(HealthService.HealthField.EXERCISE, 7));
        health.addProperty("entries", ctx.health().count());
        List<HealthEntry> entries = ctx.health().entries();
        if (!entries.isEmpty()) {
            health.add("latest", gson.toJsonTree(entries.get(entries.size() - 1)));
        }
        out.add("health", health);

        JsonObject notif = new JsonObject();
        notif.addProperty("unread", ctx.notifications().unreadCount());
        out.add("notifications", notif);
        return out;
    }

    // ------------------------------------------------------------------ tasks

    private void tasks(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.size() == 1 && method.equals("GET")) {
            String scope = query(ex, "scope", "all");
            String q = query(ex, "q", "");
            String status = query(ex, "status", "");
            String priority = query(ex, "priority", "");
            LocalDate today = LocalDate.now();
            List<Task> all = ctx.tasks().all().stream()
                    .filter(t -> q.isBlank() || t.title.toLowerCase().contains(q.toLowerCase())
                            || (t.notes != null && t.notes.toLowerCase().contains(q.toLowerCase())))
                    .filter(t -> status.isBlank() || t.status.name().equalsIgnoreCase(status))
                    .filter(t -> priority.isBlank() || t.priority.name().equalsIgnoreCase(priority))
                    .filter(t -> switch (scope) {
                        case "today" -> t.isDueToday(today);
                        case "overdue" -> t.isOverdue(today);
                        case "upcoming" -> t.dueDate != null && !t.dueDate.isBefore(today)
                                && t.dueDate.isBefore(today.plusDays(8));
                        default -> true;
                    })
                    .sorted(Comparator.comparing((Task t) -> t.status == TaskStatus.DONE)
                            .thenComparing((Task t) -> t.dueDate == null ? LocalDate.MAX : t.dueDate)
                            .thenComparing((Task t) -> t.priority.ordinal(), Comparator.reverseOrder()))
                    .toList();
            sendJson(ex, 200, taskList(all, today));
            return;
        }
        if (p.size() == 1 && method.equals("POST")) {
            JsonObject b = body(ex);
            Task created = ctx.tasks().create(
                    str(b, "title", ""), str(b, "notes", ""),
                    enumValue(Priority.class, str(b, "priority", "MEDIUM")),
                    date(b, "dueDate"), time(b, "dueTime"),
                    str(b, "group", ""), new ArrayList<>(tags(b)), recurrence(b));
            sendJson(ex, 200, taskJson(created, LocalDate.now()));
            return;
        }
        if (p.size() >= 2) {
            String id = p.get(1);
            if (method.equals("DELETE")) {
                ctx.tasks().delete(id);
                sendJson(ex, 200, okJson());
                return;
            }
            if (method.equals("POST") && p.size() == 3 && p.get(2).equals("status")) {
                JsonObject b = body(ex);
                ctx.tasks().setStatus(id, enumValue(TaskStatus.class, str(b, "status", "TODO")));
                sendJson(ex, 200, okJson());
                return;
            }
            if (method.equals("PUT")) {
                JsonObject b = body(ex);
                Task t = ctx.tasks().get(id);
                if (b.has("title")) {
                    t.title = str(b, "title", t.title);
                }
                if (b.has("notes")) {
                    t.notes = str(b, "notes", t.notes);
                }
                if (b.has("status")) {
                    t.status = enumValue(TaskStatus.class, str(b, "status", t.status.name()));
                }
                if (b.has("priority")) {
                    t.priority = enumValue(Priority.class, str(b, "priority", t.priority.name()));
                }
                if (b.has("dueDate")) {
                    t.dueDate = date(b, "dueDate");
                }
                if (b.has("dueTime")) {
                    t.dueTime = time(b, "dueTime");
                }
                if (b.has("group")) {
                    t.group = str(b, "group", t.group);
                }
                if (b.has("tags")) {
                    t.tags = tags(b);
                }
                if (b.has("recurrence")) {
                    t.recurrence = recurrence(b);
                }
                ctx.tasks().update(t);
                sendJson(ex, 200, taskJson(t, LocalDate.now()));
                return;
            }
        }
        sendError(ex, 404, "Không tìm thấy endpoint công việc");
    }

    private JsonArray taskList(List<Task> tasks, LocalDate today) {
        JsonArray arr = new JsonArray();
        for (Task t : tasks) {
            arr.add(taskJson(t, today));
        }
        return arr;
    }

    private JsonObject taskJson(Task t, LocalDate today) {
        JsonObject o = gson.toJsonTree(t).getAsJsonObject();
        o.addProperty("overdue", t.isOverdue(today));
        o.addProperty("dueToday", t.isDueToday(today));
        o.addProperty("checklistProgress", t.checklistProgress());
        return o;
    }

    private RecurrenceRule recurrence(JsonObject b) {
        RecurrenceRule rule = new RecurrenceRule();
        if (b.has("recurrence") && b.get("recurrence").isJsonObject()) {
            JsonObject r = b.getAsJsonObject("recurrence");
            rule.type = enumValue(RecurrenceType.class, str(r, "type", "NONE"));
            rule.interval = Math.max(1, num(r, "interval", 1).intValue());
            rule.daysOfWeek = new ArrayList<>();
            if (r.has("daysOfWeek") && r.get("daysOfWeek").isJsonArray()) {
                for (JsonElement e : r.getAsJsonArray("daysOfWeek")) {
                    rule.daysOfWeek.add(e.getAsInt());
                }
            }
        }
        return rule;
    }

    // ------------------------------------------------------------------ notes

    private void notes(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.size() == 1 && method.equals("GET")) {
            String q = query(ex, "q", "");
            sendJson(ex, 200, notesArray(q.isBlank() ? ctx.notes().list() : ctx.notes().search(q)));
            return;
        }
        if (p.size() == 1 && method.equals("POST")) {
            JsonObject b = body(ex);
            NoteMeta meta = ctx.notes().create(str(b, "title", "Ghi chú mới"),
                    str(b, "folder", ""), str(b, "content", ""), tags(b));
            sendJson(ex, 200, noteDto(meta));
            return;
        }
        if (p.size() == 2 && p.get(1).equals("folders")) {
            if (method.equals("GET")) {
                sendJson(ex, 200, gson.toJsonTree(ctx.notes().listFolders()));
            } else if (method.equals("POST")) {
                JsonObject b = body(ex);
                sendJson(ex, 200, gson.toJsonTree(ctx.notes().createFolder(str(b, "path", ""))));
            } else if (method.equals("PUT")) {
                JsonObject b = body(ex);
                sendJson(ex, 200, gson.toJsonTree(ctx.notes().renameFolder(
                        str(b, "path", ""), str(b, "newName", ""))));
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("folders") && p.get(2).equals("delete")
                && method.equals("POST")) {
            JsonObject b = body(ex);
            JsonObject out = new JsonObject();
            out.addProperty("deletedNotes", ctx.notes().deleteFolder(str(b, "path", "")));
            sendJson(ex, 200, out);
            return;
        }
        if (p.size() == 2) {
            String id = p.get(1);
            if (method.equals("GET")) {
                NoteMeta meta = ctx.notes().get(id);
                JsonObject out = new JsonObject();
                out.add("note", noteDto(meta));
                out.addProperty("content", ctx.notes().readContent(id));
                sendJson(ex, 200, out);
                return;
            }
            if (method.equals("PUT")) {
                JsonObject b = body(ex);
                NoteMeta meta = ctx.notes().get(id);
                Set<String> newTags = b.has("tags") ? tags(b) : null;
                ctx.notes().updateMeta(meta,
                        b.has("title") ? str(b, "title", meta.title) : null,
                        b.has("folder") ? str(b, "folder", meta.folder) : null,
                        newTags,
                        b.has("starred") ? b.get("starred").getAsBoolean() : meta.starred);
                if (b.has("content")) {
                    ctx.notes().saveContent(id, str(b, "content", ""), null);
                }
                JsonObject out = new JsonObject();
                out.add("note", noteDto(ctx.notes().get(id)));
                out.addProperty("content", ctx.notes().readContent(id));
                sendJson(ex, 200, out);
                return;
            }
            if (method.equals("DELETE")) {
                ctx.notes().delete(id);
                sendJson(ex, 200, okJson());
                return;
            }
        }
        sendError(ex, 404, "Không tìm thấy endpoint ghi chú");
    }

    private JsonArray notesArray(List<NoteMeta> notes) {
        JsonArray arr = new JsonArray();
        for (NoteMeta n : notes) {
            arr.add(noteDto(n));
        }
        return arr;
    }

    private JsonObject noteDto(NoteMeta n) {
        JsonObject o = gson.toJsonTree(n).getAsJsonObject();
        o.remove("contentFile");
        o.remove("lastSavedHash");
        return o;
    }

    private void markdown(HttpExchange ex, String method) throws IOException {
        if (!method.equals("POST")) {
            sendError(ex, 405, "Chỉ hỗ trợ POST");
            return;
        }
        JsonObject b = body(ex);
        JsonObject out = new JsonObject();
        out.addProperty("html", ctx.markdownService().toHtml(str(b, "text", "")));
        sendJson(ex, 200, out);
    }

    // ------------------------------------------------------------------ finance

    private void finance(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.size() == 1 && method.equals("GET")) {
            YearMonth month = monthParam(query(ex, "month", YearMonth.now().toString()));
            synchronized (moduleLock) {
                JsonObject out = new JsonObject();
                out.add("summary", gson.toJsonTree(ctx.finance().monthSummary(month)));
                out.add("budgets", gson.toJsonTree(ctx.finance().budgetStatus(month)));
                out.add("series", gson.toJsonTree(ctx.finance().dailySeries(
                        month.atDay(1), month.atEndOfMonth())));
                out.add("categories", gson.toJsonTree(ctx.finance().categories(null)));
                out.add("debts", gson.toJsonTree(ctx.finance().debts(null, null)));
                sendJson(ex, 200, out);
            }
            return;
        }
        if (p.size() == 2 && p.get(1).equals("transactions")) {
            if (method.equals("GET")) {
                YearMonth month = monthParam(query(ex, "month", YearMonth.now().toString()));
                synchronized (moduleLock) {
                    sendJson(ex, 200, gson.toJsonTree(
                            ctx.finance().inRange(month.atDay(1), month.atEndOfMonth())));
                }
            } else if (method.equals("POST")) {
                JsonObject b = body(ex);
                synchronized (moduleLock) {
                    var tx = ctx.finance().addTransaction(
                            date(b, "date"),
                            enumValue(TxType.class, str(b, "type", "EXPENSE")),
                            decimal(b, "amount"),
                            str(b, "categoryId", ""),
                            b.has("method") ? enumValue(com.lifedesk.finance.PaymentMethod.class,
                                    str(b, "method", "CASH")) : com.lifedesk.finance.PaymentMethod.CASH,
                            str(b, "description", ""), tags(b), null, true);
                    sendJson(ex, 200, gson.toJsonTree(tx));
                }
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("transactions")) {
            String id = p.get(2);
            synchronized (moduleLock) {
                if (method.equals("DELETE")) {
                    ctx.finance().deleteTransaction(id);
                    sendJson(ex, 200, okJson());
                } else if (method.equals("PUT")) {
                    JsonObject b = body(ex);
                    var existing = ctx.finance().all().stream()
                            .filter(t -> t.id.equals(id)).findFirst()
                            .orElseThrow(() -> new NotFoundException("Không tìm thấy giao dịch"));
                    if (b.has("date")) {
                        existing.date = date(b, "date");
                    }
                    if (b.has("type")) {
                        existing.type = enumValue(TxType.class, str(b, "type", existing.type.name()));
                    }
                    if (b.has("amount")) {
                        existing.amount = decimal(b, "amount");
                    }
                    if (b.has("categoryId")) {
                        existing.categoryId = str(b, "categoryId", existing.categoryId);
                    }
                    if (b.has("method")) {
                        existing.method = enumValue(com.lifedesk.finance.PaymentMethod.class,
                                str(b, "method", existing.method.name()));
                    }
                    if (b.has("description")) {
                        existing.description = str(b, "description", existing.description);
                    }
                    if (b.has("tags")) {
                        existing.tags = tags(b);
                    }
                    ctx.finance().updateTransaction(existing);
                    sendJson(ex, 200, gson.toJsonTree(existing));
                }
            }
            return;
        }
        if (p.size() == 2 && p.get(1).equals("categories")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.finance().addCategory(
                        str(b, "name", "Danh mục"), enumValue(TxType.class,
                                str(b, "type", "EXPENSE")))));
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("categories") && method.equals("DELETE")) {
            synchronized (moduleLock) {
                ctx.finance().deleteCategory(p.get(2));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 2 && p.get(1).equals("budgets")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.finance().addBudget(
                        monthParam(str(b, "month", YearMonth.now().toString())),
                        str(b, "categoryId", ""), decimal(b, "limit"),
                        b.has("recurring") && b.get("recurring").getAsBoolean())));
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("budgets") && method.equals("DELETE")) {
            synchronized (moduleLock) {
                ctx.finance().deleteBudget(p.get(2));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 2 && p.get(1).equals("debts") && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.finance().addDebt(
                        enumValue(com.lifedesk.finance.DebtDirection.class,
                                str(b, "direction", "PAYABLE")),
                        str(b, "party", ""), decimal(b, "amount"),
                        date(b, "incurredDate"), date(b, "dueDate"), str(b, "note", ""))));
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("debts") && p.get(2).equals("settle")) {
            synchronized (moduleLock) {
                ctx.finance().settleDebt(p.get(2));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        sendError(ex, 404, "Không tìm thấy endpoint tài chính");
    }

    // ------------------------------------------------------------------ habits

    private void habits(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.size() == 1 && method.equals("GET")) {
            LocalDate today = LocalDate.now();
            JsonObject out = new JsonObject();
            JsonArray arr = new JsonArray();
            synchronized (moduleLock) {
                for (Habit h : ctx.habits().habits(false)) {
                    JsonObject o = gson.toJsonTree(h).getAsJsonObject();
                    o.addProperty("completedToday", ctx.habits().isCompleted(h.id, today));
                    o.addProperty("streak", ctx.habits().currentStreak(h));
                    o.addProperty("completionRate", ctx.habits().completionRate(h, 30));
                    arr.add(o);
                }
                out.add("habits", arr);
                out.add("goals", gson.toJsonTree(ctx.habits().goals()));
            }
            sendJson(ex, 200, out);
            return;
        }
        if (p.size() == 1 && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                Set<java.time.DayOfWeek> days = new LinkedHashSet<>();
                if (b.has("days")) {
                    for (JsonElement e : b.getAsJsonArray("days")) {
                        days.add(java.time.DayOfWeek.valueOf(e.getAsString()));
                    }
                }
                Habit h = ctx.habits().addHabit(str(b, "name", "Thói quen"),
                        str(b, "description", ""), days, str(b, "color", "accent"));
                sendJson(ex, 200, gson.toJsonTree(h));
            }
            return;
        }
        if (p.size() == 2 && p.get(1).equals("week") && method.equals("GET")) {
            LocalDate start = LocalDate.parse(query(ex, "start",
                    LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString()));
            JsonArray arr = new JsonArray();
            synchronized (moduleLock) {
                for (Habit h : ctx.habits().habits(false)) {
                    JsonObject o = gson.toJsonTree(h).getAsJsonObject();
                    JsonArray days = new JsonArray();
                    for (int i = 0; i < 7; i++) {
                        LocalDate d = start.plusDays(i);
                        JsonObject day = new JsonObject();
                        day.addProperty("date", d.toString());
                        day.addProperty("completed", ctx.habits().isCompleted(h.id, d));
                        day.addProperty("scheduled", h.scheduledOn(d.getDayOfWeek()));
                        days.add(day);
                    }
                    o.add("week", days);
                    arr.add(o);
                }
            }
            sendJson(ex, 200, arr);
            return;
        }
        if (p.size() == 2 && p.get(1).equals("goals") && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.habits().addGoal(
                        str(b, "title", "Mục tiêu"), str(b, "description", ""),
                        date(b, "targetDate"))));
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("goals") && method.equals("DELETE")) {
            synchronized (moduleLock) {
                ctx.habits().deleteGoal(p.get(2));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 3 && p.get(2).equals("completion")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                ctx.habits().setCompletion(p.get(1), date(b, "date"),
                        b.has("completed") && b.get("completed").getAsBoolean(),
                        str(b, "reason", ""), "user");
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 2 && method.equals("DELETE")) {
            synchronized (moduleLock) {
                ctx.habits().deleteHabit(p.get(1));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 2 && method.equals("PUT")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                Habit h = ctx.habits().habit(p.get(1));
                if (b.has("name")) {
                    h.name = str(b, "name", h.name);
                }
                if (b.has("description")) {
                    h.description = str(b, "description", h.description);
                }
                if (b.has("color")) {
                    h.color = str(b, "color", h.color);
                }
                if (b.has("days")) {
                    h.scheduleDays = new LinkedHashSet<>();
                    for (JsonElement e : b.getAsJsonArray("days")) {
                        h.scheduleDays.add(java.time.DayOfWeek.valueOf(e.getAsString()));
                    }
                }
                ctx.habits().updateHabit(h, "Chỉnh sửa trên giao diện", "user");
                sendJson(ex, 200, gson.toJsonTree(h));
            }
            return;
        }
        sendError(ex, 404, "Không tìm thấy endpoint thói quen");
    }

    // ------------------------------------------------------------------ health

    private void health(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.size() == 1 && method.equals("GET")) {
            JsonObject out = new JsonObject();
            synchronized (moduleLock) {
                out.add("entries", gson.toJsonTree(ctx.health().entries()));
                out.add("metrics", gson.toJsonTree(ctx.health().metrics()));
                out.addProperty("avgSleep7", ctx.health().averageDays(HealthService.HealthField.SLEEP, 7));
                out.addProperty("avgExercise7", ctx.health().averageDays(HealthService.HealthField.EXERCISE, 7));
                out.addProperty("avgWeight7", ctx.health().averageDays(HealthService.HealthField.WEIGHT, 7));
                out.addProperty("disclaimer", HealthService.DISCLAIMER);
            }
            sendJson(ex, 200, out);
            return;
        }
        if (p.size() == 1 && method.equals("POST")) {
            JsonObject b = body(ex);
            Map<String, BigDecimal> custom = new java.util.LinkedHashMap<>();
            if (b.has("customMetrics") && b.get("customMetrics").isJsonObject()) {
                for (String k : b.getAsJsonObject("customMetrics").keySet()) {
                    custom.put(k, b.getAsJsonObject("customMetrics").get(k).getAsBigDecimal());
                }
            }
            synchronized (moduleLock) {
                var entry = ctx.health().upsert(date(b, "date"),
                        b.has("sleepHours") && !b.get("sleepHours").isJsonNull()
                                ? b.get("sleepHours").getAsBigDecimal() : null,
                        b.has("exerciseMinutes") && !b.get("exerciseMinutes").isJsonNull()
                                ? b.get("exerciseMinutes").getAsInt() : null,
                        b.has("weightKg") && !b.get("weightKg").isJsonNull()
                                ? b.get("weightKg").getAsBigDecimal() : null,
                        custom, str(b, "note", ""));
                sendJson(ex, 200, gson.toJsonTree(entry));
            }
            return;
        }
        if (p.size() == 2 && method.equals("DELETE")) {
            synchronized (moduleLock) {
                ctx.health().deleteEntry(LocalDate.parse(p.get(1)));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 2 && p.get(1).equals("metrics") && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.health().addMetric(
                        str(b, "name", "Chỉ số"), str(b, "unit", ""),
                        b.has("target") && !b.get("target").isJsonNull()
                                ? b.get("target").getAsBigDecimal() : null)));
            }
            return;
        }
        sendError(ex, 404, "Không tìm thấy endpoint sức khỏe");
    }

    // ------------------------------------------------------------------ learning

    private void learning(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.size() == 1 && method.equals("GET")) {
            JsonObject out = new JsonObject();
            synchronized (moduleLock) {
                JsonArray decks = new JsonArray();
                for (Deck d : ctx.learning().decks()) {
                    JsonObject o = gson.toJsonTree(d).getAsJsonObject();
                    o.addProperty("dueCount", ctx.learning().dueCountForDeck(d.id));
                    o.addProperty("cardCount", ctx.learning().cardsInDeck(d.id).size());
                    decks.add(o);
                }
                out.add("courses", gson.toJsonTree(ctx.learning().courses()));
                out.add("decks", decks);
                out.addProperty("dueCards", ctx.learning().dueCount());
                out.addProperty("cardCount", ctx.learning().cardCount());
                out.add("sessions", gson.toJsonTree(ctx.learning().sessions().stream()
                        .sorted(Comparator.comparing((com.lifedesk.learning.StudySession s) -> s.date).reversed())
                        .limit(20).toList()));
            }
            sendJson(ex, 200, out);
            return;
        }
        if (p.size() == 2 && p.get(1).equals("courses") && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.learning().addCourse(
                        str(b, "title", "Khóa học"), str(b, "source", ""),
                        str(b, "link", ""), tags(b))));
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("courses") && method.equals("DELETE")) {
            synchronized (moduleLock) {
                ctx.learning().deleteCourse(p.get(2));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 3 && p.get(1).equals("courses") && method.equals("PUT")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                Course c = ctx.learning().course(p.get(2));
                if (b.has("status")) {
                    c.status = enumValue(com.lifedesk.learning.CourseStatus.class,
                            str(b, "status", c.status.name()));
                }
                if (b.has("progressPercent")) {
                    c.progressPercent = b.get("progressPercent").getAsInt();
                }
                if (b.has("title")) {
                    c.title = str(b, "title", c.title);
                }
                if (b.has("source")) {
                    c.source = str(b, "source", c.source);
                }
                if (b.has("link")) {
                    c.link = str(b, "link", c.link);
                }
                ctx.learning().updateCourse(c);
                sendJson(ex, 200, gson.toJsonTree(c));
            }
            return;
        }
        if (p.size() == 2 && p.get(1).equals("decks") && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.learning().addDeck(
                        str(b, "name", "Bộ thẻ"), str(b, "courseId", ""))));
            }
            return;
        }
        if (p.size() == 4 && p.get(1).equals("decks") && p.get(3).equals("cards")
                && method.equals("GET")) {
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.learning().cardsInDeck(p.get(2))));
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("decks") && method.equals("DELETE")) {
            synchronized (moduleLock) {
                ctx.learning().deleteDeck(p.get(2));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 2 && p.get(1).equals("cards") && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                sendJson(ex, 200, gson.toJsonTree(ctx.learning().addCard(
                        str(b, "deckId", ""), str(b, "front", ""), str(b, "back", ""))));
            }
            return;
        }
        if (p.size() == 3 && p.get(1).equals("cards") && method.equals("DELETE")) {
            synchronized (moduleLock) {
                ctx.learning().deleteCard(p.get(2));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 4 && p.get(1).equals("cards") && p.get(3).equals("review")
                && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                ctx.learning().review(p.get(2), b.has("correct") && b.get("correct").getAsBoolean());
                Flashcard c = ctx.learning().card(p.get(2));
                sendJson(ex, 200, gson.toJsonTree(c));
            }
            return;
        }
        if (p.size() == 2 && p.get(1).equals("sessions") && method.equals("POST")) {
            JsonObject b = body(ex);
            synchronized (moduleLock) {
                ctx.learning().addSession(str(b, "courseId", ""), date(b, "date"),
                        num(b, "minutes", 30).intValue(), str(b, "topic", ""));
            }
            sendJson(ex, 200, okJson());
            return;
        }
        sendError(ex, 404, "Không tìm thấy endpoint học tập");
    }

    // ------------------------------------------------------------------ files

    // ------------------------------------------------------------------ AI

    private void ai(HttpExchange ex, String method, List<String> p) throws Exception {
        if (p.size() == 2 && p.get(1).equals("conversations") && method.equals("GET")) {
            sendJson(ex, 200, gson.toJsonTree(ctx.aiConversations().list()));
            return;
        }
        if (p.size() == 2 && p.get(1).equals("conversations") && method.equals("POST")) {
            JsonObject b = body(ex);
            var conv = new com.lifedesk.ai.AiConversationService.Conversation();
            conv.title = str(b, "title", "Hội thoại mới");
            ctx.aiConversations().upsert(conv);
            sendJson(ex, 200, gson.toJsonTree(conv));
            return;
        }
        if (p.size() == 3 && p.get(1).equals("conversations")) {
            String id = p.get(2);
            if (method.equals("GET")) {
                sendJson(ex, 200, gson.toJsonTree(ctx.aiConversations().get(id)
                        .orElseThrow(() -> new NotFoundException("Không tìm thấy hội thoại"))));
                return;
            }
            if (method.equals("DELETE")) {
                ctx.aiConversations().delete(id);
                sendJson(ex, 200, okJson());
                return;
            }
        }
        if (p.size() == 2 && p.get(1).equals("chat") && method.equals("POST")) {
            JsonObject b = body(ex);
            String conversationId = str(b, "conversationId", "");
            String message = str(b, "message", "");
            if (message.isBlank()) {
                throw new ValidationException("Nội dung tin nhắn không được để trống");
            }
            var svc = ctx.aiConversations();
            var conv = svc.get(conversationId).orElseGet(() -> {
                var c = new com.lifedesk.ai.AiConversationService.Conversation();
                c.title = message.length() > 48 ? message.substring(0, 48) : message;
                return c;
            });
            List<com.lifedesk.ai.ChatMessage> history = conv.messages.stream()
                    .map(m -> new com.lifedesk.ai.ChatMessage(m.role, m.content)).toList();
            conv.messages.add(new com.lifedesk.ai.AiConversationService.Message("user", message));
            svc.upsert(conv);

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> answer = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ctx.ai().chatAsync(ctx.executors().ai(), history, message,
                    reply -> {
                        answer.set(reply);
                        latch.countDown();
                    },
                    error -> {
                        failure.set(error);
                        latch.countDown();
                    });
            if (!latch.await(180, TimeUnit.SECONDS)) {
                throw new ValidationException("AI không phản hồi trong thời gian cho phép");
            }
            if (failure.get() != null) {
                throw new ValidationException(failure.get().getMessage() == null
                        ? failure.get().toString() : failure.get().getMessage());
            }
            String reply = answer.get() == null ? "" : answer.get();
            conv.messages.add(new com.lifedesk.ai.AiConversationService.Message("assistant", reply));
            svc.upsert(conv);
            JsonObject out = new JsonObject();
            out.addProperty("reply", reply);
            out.add("conversation", gson.toJsonTree(conv));
            sendJson(ex, 200, out);
            return;
        }
        sendError(ex, 404, "Không tìm thấy endpoint AI");
    }

    // ------------------------------------------------------------------ agent

    // ------------------------------------------------------------------ RSS

    // ------------------------------------------------------------------ library

    private void library(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.size() == 1 && method.equals("GET")) {
            JsonObject out = new JsonObject();
            out.add("items", gson.toJsonTree(ctx.library().all()));
            out.add("stats", gson.toJsonTree(ctx.library().stats()));
            sendJson(ex, 200, out);
            return;
        }
        if (p.size() == 1 && method.equals("POST")) {
            JsonObject b = body(ex);
            sendJson(ex, 200, gson.toJsonTree(ctx.library().add(
                    str(b, "title", ""),
                    enumValue(com.lifedesk.library.LibraryService.Kind.class,
                            str(b, "kind", "BOOK")),
                    str(b, "creator", ""), num(b, "year", 0).intValue(),
                    str(b, "coverUrl", ""))));
            return;
        }
        if (p.size() == 3 && p.get(1).equals("tick") && method.equals("POST")) {
            ctx.library().tickProgress(p.get(2));
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 2 && method.equals("PUT")) {
            JsonObject b = body(ex);
            var item = ctx.library().all().stream().filter(i -> i.id.equals(p.get(1)))
                    .findFirst().orElseThrow(() -> new NotFoundException("Không tìm thấy mục"));
            if (b.has("status")) {
                item.status = enumValue(com.lifedesk.library.LibraryService.Status.class,
                        str(b, "status", item.status.name()));
            }
            if (b.has("rating")) {
                item.rating = num(b, "rating", item.rating).intValue();
            }
            if (b.has("progressCurrent")) {
                item.progressCurrent = num(b, "progressCurrent", item.progressCurrent).intValue();
            }
            if (b.has("progressTotal")) {
                item.progressTotal = num(b, "progressTotal", item.progressTotal).intValue();
            }
            if (b.has("note")) {
                item.note = str(b, "note", item.note);
            }
            if (b.has("link")) {
                item.link = str(b, "link", item.link);
            }
            ctx.library().update(item);
            sendJson(ex, 200, gson.toJsonTree(item));
            return;
        }
        if (p.size() == 2 && method.equals("DELETE")) {
            ctx.library().delete(p.get(1));
            sendJson(ex, 200, okJson());
            return;
        }
        sendError(ex, 404, "Không tìm thấy endpoint thư viện");
    }

    // ------------------------------------------------------------------ notifications / search / config

    private void notifications(HttpExchange ex, String method, List<String> p) throws IOException {
        if (p.size() == 1 && method.equals("GET")) {
            JsonObject out = new JsonObject();
            out.add("items", gson.toJsonTree(ctx.notifications().all()));
            out.addProperty("unread", ctx.notifications().unreadCount());
            sendJson(ex, 200, out);
            return;
        }
        if (p.size() == 2 && p.get(1).equals("read-all") && method.equals("POST")) {
            ctx.notifications().markAllRead();
            sendJson(ex, 200, okJson());
            return;
        }
        if (p.size() == 1 && method.equals("DELETE")) {
            ctx.notifications().clear();
            sendJson(ex, 200, okJson());
            return;
        }
        sendError(ex, 404, "Không tìm thấy endpoint thông báo");
    }

    private void search(HttpExchange ex, String method) throws IOException {
        if (!method.equals("GET")) {
            sendError(ex, 405, "Chỉ hỗ trợ GET");
            return;
        }
        String q = query(ex, "q", "");
        String types = query(ex, "type", "");
        Set<String> filter = new LinkedHashSet<>();
        if (!types.isBlank()) {
            for (String t : types.split(",")) {
                filter.add(t.trim().toUpperCase());
            }
        }
        if (q.isBlank()) {
            sendJson(ex, 200, new JsonArray());
            return;
        }
        sendJson(ex, 200, gson.toJsonTree(ctx.search().query(q, filter, 8)));
    }

    private void config(HttpExchange ex, String method) throws IOException {
        if (method.equals("GET")) {
            sendJson(ex, 200, bootstrap());
            return;
        }
        if (!method.equals("PUT")) {
            sendError(ex, 405, "Chỉ hỗ trợ GET/PUT");
            return;
        }
        JsonObject b = body(ex);
        var cfg = ctx.config();
        if (b.has("theme")) {
            cfg.theme = str(b, "theme", cfg.theme);
        }
        if (b.has("sidebarCollapsed")) {
            cfg.sidebarCollapsed = b.get("sidebarCollapsed").getAsBoolean();
        }
        if (b.has("remindersEnabled")) {
            cfg.remindersEnabled = b.get("remindersEnabled").getAsBoolean();
        }
        if (b.has("reminderMinutesBefore")) {
            cfg.reminderMinutesBefore = num(b, "reminderMinutesBefore", cfg.reminderMinutesBefore).intValue();
        }
        if (b.has("latexAssist")) {
            cfg.latexAssist = b.get("latexAssist").getAsBoolean();
        }
        if (b.has("ai") && b.get("ai").isJsonObject()) {
            JsonObject ai = b.getAsJsonObject("ai");
            if (ai.has("enabled")) {
                cfg.ai.enabled = ai.get("enabled").getAsBoolean();
            }
            if (ai.has("providerId")) {
                cfg.ai.providerId = str(ai, "providerId", cfg.ai.providerId);
            }
            if (ai.has("includeUserData")) {
                cfg.ai.includeUserData = ai.get("includeUserData").getAsBoolean();
            }
            if (ai.has("maxHistoryMessages")) {
                cfg.ai.maxHistoryMessages = num(ai, "maxHistoryMessages",
                        cfg.ai.maxHistoryMessages).intValue();
            }
            if (ai.has("ollama") && ai.get("ollama").isJsonObject()) {
                JsonObject o = ai.getAsJsonObject("ollama");
                if (o.has("baseUrl")) {
                    cfg.ai.ollama.baseUrl = str(o, "baseUrl", cfg.ai.ollama.baseUrl);
                }
                if (o.has("model")) {
                    cfg.ai.ollama.model = str(o, "model", cfg.ai.ollama.model);
                }
            }
            if (ai.has("kilo") && ai.get("kilo").isJsonObject()) {
                JsonObject k = ai.getAsJsonObject("kilo");
                if (k.has("baseUrl")) {
                    cfg.ai.kilo.baseUrl = str(k, "baseUrl", cfg.ai.kilo.baseUrl);
                }
                if (k.has("model")) {
                    cfg.ai.kilo.model = str(k, "model", cfg.ai.kilo.model);
                }
                String key = str(k, "apiKey", "");
                if (!key.isBlank()) {
                    cfg.ai.kilo.apiKey = key;
                }
            }
        }
        new ConfigService(ConfigService.defaultConfigDir()).save(cfg);
        ctx.persistAiSnapshot();
        sendJson(ex, 200, bootstrap());
    }

    // ------------------------------------------------------------------ static files

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            sendError(ex, 405, "Chỉ hỗ trợ GET");
            return;
        }
        String clean = path.equals("/") ? "/index.html" : path;
        if (clean.contains("..")) {
            sendError(ex, 400, "Đường dẫn không hợp lệ");
            return;
        }
        InputStream in = getClass().getResourceAsStream("/web" + clean);
        if (in == null) {
            in = getClass().getResourceAsStream("/web/index.html");
        }
        if (in == null) {
            sendError(ex, 404, "UI chưa được build (thiếu resources/web)");
            return;
        }
        try {
            byte[] bytes = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", mime(clean));
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
        } finally {
            in.close();
        }
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (p.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (p.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (p.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (p.endsWith(".png")) {
            return "image/png";
        }
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (p.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (p.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------ helpers

    private void sendJson(HttpExchange ex, int status, JsonElement body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private void sendError(HttpExchange ex, int status, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("error", message == null ? "Lỗi không xác định" : message);
        sendJson(ex, status, o);
    }

    private JsonObject okJson() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private JsonObject body(HttpExchange ex) throws IOException {
        String s = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return s.isBlank() ? new JsonObject() : JsonParser.parseString(s).getAsJsonObject();
    }

    private static String query(HttpExchange ex, String key, String fallback) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return fallback;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(key)) {
                return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1),
                        StandardCharsets.UTF_8);
            }
        }
        return fallback;
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static BigDecimal decimal(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBigDecimal() : BigDecimal.ZERO;
    }

    private static Number num(JsonObject o, String key, Number fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsNumber() : fallback;
    }

    private static LocalDate date(JsonObject o, String key) {
        String v = str(o, key, "");
        return v.isBlank() ? null : LocalDate.parse(v);
    }

    private static LocalTime time(JsonObject o, String key) {
        String v = str(o, key, "");
        return v.isBlank() ? null : LocalTime.parse(v);
    }

    private static Set<String> tags(JsonObject o) {
        Set<String> out = new LinkedHashSet<>();
        if (o.has("tags") && o.get("tags").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("tags")) {
                if (!e.getAsString().isBlank()) {
                    out.add(e.getAsString().trim());
                }
            }
        }
        return out;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Giá trị không hợp lệ: " + value);
        }
    }

    private static YearMonth monthParam(String value) {
        try {
            return YearMonth.parse(value);
        } catch (RuntimeException e) {
            throw new ValidationException("Tháng không hợp lệ: " + value);
        }
    }
}
