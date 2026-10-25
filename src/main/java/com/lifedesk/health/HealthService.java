package com.lifedesk.health;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Manual health tracking: sleep, exercise, weight and user-chosen metrics.
 * Strictly a personal log + trend charts. The app never diagnoses or gives
 * medical advice (enforced by simply having no such feature and stating the
 * disclaimer in the UI).
 */
public class HealthService {
    public static final String DISCLAIMER =
            "LifeDesk chỉ là công cụ ghi nhận thủ công, không đưa ra chẩn đoán hay tư vấn y tế.";

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();
    private final List<HealthEntry> entries = new ArrayList<>();
    private final List<MetricDef> metrics = new ArrayList<>();

    public HealthService(JsonStore store) {
        this.store = store;
        Type entryType = new TypeToken<List<HealthEntry>>() {
        }.getType();
        Type metricType = new TypeToken<List<MetricDef>>() {
        }.getType();
        entries.addAll(readList("health/entries.json", "entries", entryType));
        metrics.addAll(readList("health/metrics.json", "metrics", metricType));
    }

    public ChangeNotifier changes() {
        return change;
    }

    private <T> List<T> readList(String file, String key, Type listType) {
        JsonStore.StoredDoc doc = store.read(file, SchemaMigrator.none(1));
        if (doc == null || !doc.data().has(key) || !doc.data().get(key).isJsonArray()) {
            return new ArrayList<>();
        }
        List<T> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray(key), listType);
        return parsed == null ? new ArrayList<>() : parsed;
    }

    private void persistEntries() {
        JsonObject data = new JsonObject();
        data.add("entries", GsonFactory.create().toJsonTree(entries));
        store.write("health/entries.json", 1, data);
    }

    private void persistMetrics() {
        JsonObject data = new JsonObject();
        data.add("metrics", GsonFactory.create().toJsonTree(metrics));
        store.write("health/metrics.json", 1, data);
    }

    public List<HealthEntry> entries() {
        return entries.stream().sorted(Comparator.comparing(e -> e.date)).toList();
    }

    public Optional<HealthEntry> byDate(LocalDate date) {
        return entries.stream().filter(e -> e.date.equals(date)).findFirst();
    }

    /** Create or update the entry for a date. */
    public HealthEntry upsert(LocalDate date, BigDecimal sleepHours, Integer exerciseMinutes,
                              BigDecimal weightKg, java.util.Map<String, BigDecimal> customMetrics, String note) {
        Validators.requireNonNull(date, "Ngày");
        validatePositive(sleepHours, "Giờ ngủ", 24);
        if (exerciseMinutes != null && (exerciseMinutes < 0 || exerciseMinutes > 1440)) {
            throw new ValidationException("Phút vận động phải từ 0 đến 1440");
        }
        validatePositive(weightKg, "Cân nặng", 500);
        HealthEntry entry = byDate(date).orElseGet(() -> {
            HealthEntry e = new HealthEntry();
            e.date = date;
            entries.add(e);
            return e;
        });        entry.sleepHours = sleepHours;
        entry.exerciseMinutes = exerciseMinutes;
        entry.weightKg = weightKg;
        entry.customMetrics = customMetrics == null ? new java.util.HashMap<>() : new java.util.HashMap<>(customMetrics);
        entry.note = note == null ? "" : note;
        persistEntries();
        change.fire();
        return entry;
    }

    private void validatePositive(BigDecimal value, String field, double max) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0 || value.doubleValue() > max) {
            throw new ValidationException(field + " không hợp lệ (0-" + max + ")");
        }
    }

    public void deleteEntry(LocalDate date) {
        if (!entries.removeIf(e -> e.date.equals(date))) {
            throw new ValidationException("Không có dữ liệu cho ngày " + date);
        }
        persistEntries();
        change.fire();
    }

    // ------------------------------------------------------------------ metrics

    public List<MetricDef> metrics() {
        return metrics.stream().sorted(Comparator.comparing(m -> m.name)).toList();
    }

    public MetricDef addMetric(String name, String unit, BigDecimal target) {
        final String trimmed = Validators.requireNonBlank(name, "Tên chỉ số");
        boolean exists = metrics.stream().anyMatch(m -> m.name.equalsIgnoreCase(trimmed));
        if (exists) {
            throw new ValidationException("Chỉ số đã tồn tại: " + trimmed);
        }
        MetricDef def = new MetricDef(trimmed, unit, target);
        metrics.add(def);
        persistMetrics();
        change.fire();
        return def;
    }

    public MetricDef metric(String id) {
        return metrics.stream().filter(m -> m.id.equals(id)).findFirst()
                .orElseThrow(() -> new com.lifedesk.common.NotFoundException("Không tìm thấy chỉ số: " + id));
    }

    public void updateMetric(String id, String name, String unit, BigDecimal target) {
        MetricDef def = metric(id);
        String trimmed = Validators.requireNonBlank(name, "Tên chỉ số");
        boolean dup = metrics.stream().anyMatch(m -> !m.id.equals(id) && m.name.equalsIgnoreCase(trimmed));
        if (dup) {
            throw new ValidationException("Chỉ số đã tồn tại: " + trimmed);
        }
        def.name = trimmed;
        def.unit = unit == null ? "" : unit;
        def.target = target;
        persistMetrics();
        change.fire();
    }

    public void deleteMetric(String id) {
        metrics.removeIf(m -> m.id.equals(id));
        for (HealthEntry e : entries) {
            e.customMetrics.remove(id);
        }
        persistMetrics();
        persistEntries();
        change.fire();
    }

    // ------------------------------------------------------------------ trends

    public record SeriesPoint(LocalDate date, BigDecimal value) {
    }

    /** Sorted series of one built-in field between two dates. */
    public List<SeriesPoint> series(LocalDate from, LocalDate to, HealthField field) {
        TreeMap<LocalDate, BigDecimal> map = new TreeMap<>();
        for (HealthEntry e : entries()) {
            if (e.date.isBefore(from) || e.date.isAfter(to)) {
                continue;
            }
            BigDecimal v = switch (field) {
                case SLEEP -> e.sleepHours;
                case WEIGHT -> e.weightKg;
                case EXERCISE -> e.exerciseMinutes == null ? null : BigDecimal.valueOf(e.exerciseMinutes);
            };
            if (v != null) {
                map.put(e.date, v);
            }
        }
        return map.entrySet().stream().map(e -> new SeriesPoint(e.getKey(), e.getValue())).toList();
    }

    /** Series for a custom metric. */
    public List<SeriesPoint> customSeries(LocalDate from, LocalDate to, String metricId) {
        TreeMap<LocalDate, BigDecimal> map = new TreeMap<>();
        for (HealthEntry e : entries()) {
            if (e.date.isBefore(from) || e.date.isAfter(to)) {
                continue;
            }
            BigDecimal v = e.customMetrics.get(metricId);
            if (v != null) {
                map.put(e.date, v);
            }
        }
        return map.entrySet().stream().map(e -> new SeriesPoint(e.getKey(), e.getValue())).toList();
    }

    public enum HealthField {
        SLEEP, WEIGHT, EXERCISE
    }

    /** Average of a field over the last {@code days} days (null when no data). */
    public Double averageDays(HealthField field, int days) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(days - 1L);
        List<SeriesPoint> pts = series(from, to, field);
        if (pts.isEmpty()) {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (SeriesPoint p : pts) {
            sum = sum.add(p.value());
        }
        return sum.divide(BigDecimal.valueOf(pts.size()), 2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    public int count() {
        return entries.size();
    }
}
