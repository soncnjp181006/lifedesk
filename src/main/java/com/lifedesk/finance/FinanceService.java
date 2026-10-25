package com.lifedesk.finance;

import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.CsvUtil;
import com.lifedesk.common.Fingerprint;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;
import com.lifedesk.storage.GsonFactory;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Personal finance business logic.
 *
 * Money is {@link BigDecimal} end to end — never double. Transactions are
 * partitioned into one JSON file per year (finance/transactions-YYYY.json)
 * so the store stays small and fast. CSV import de-duplicates re-imported
 * rows using a stable fingerprint (date|type|amount|description|externalId).
 * Nothing ever leaves the machine.
 */
public class FinanceService {
    private static final Logger LOG = LoggerFactory.getLogger(FinanceService.class);
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();

    private final Map<Integer, List<Transaction>> txByYear = new HashMap<>();
    private final List<Category> categories = new ArrayList<>();
    private final List<Budget> budgets = new ArrayList<>();
    private final List<Debt> debts = new ArrayList<>();
    private final Set<String> fingerprints = new HashSet<>();

    public FinanceService(JsonStore store) {
        this.store = store;
        loadCategories();
        loadBudgets();
        loadDebts();
        loadFingerprints();
        if (categories.isEmpty()) {
            seedDefaultCategories();
        }
    }

    public ChangeNotifier changes() {
        return change;
    }

    // ------------------------------------------------------------------ categories

    private void loadCategories() {
        Type listType = new TypeToken<List<Category>>() {
        }.getType();
        List<Category> parsed = readList("finance/categories.json", "categories", listType);
        categories.addAll(parsed);
    }

    private void loadBudgets() {
        Type listType = new TypeToken<List<Budget>>() {
        }.getType();
        budgets.addAll(readList("finance/budgets.json", "budgets", listType));
    }

    private void loadDebts() {
        Type listType = new TypeToken<List<Debt>>() {
        }.getType();
        debts.addAll(readList("finance/debts.json", "debts", listType));
    }

    private void loadFingerprints() {
        Type listType = new TypeToken<List<String>>() {
        }.getType();
        List<String> parsed = readList("finance/fingerprints.json", "fingerprints", listType);
        fingerprints.addAll(parsed);
    }

    private <T> List<T> readList(String file, String key, Type listType) {
        JsonStore.StoredDoc doc = store.read(file, SchemaMigrator.none(1));
        if (doc == null || !doc.data().has(key) || !doc.data().get(key).isJsonArray()) {
            return new ArrayList<>();
        }
        List<T> parsed = GsonFactory.create().fromJson(doc.data().getAsJsonArray(key), listType);
        return parsed == null ? new ArrayList<>() : parsed;
    }

    private void writeList(String file, String key, Object list) {
        JsonObject data = new JsonObject();
        data.add(key, GsonFactory.create().toJsonTree(list));
        store.write(file, 1, data);
    }

    private void persistCategories() {
        writeList("finance/categories.json", "categories", categories);
    }

    private void persistBudgets() {
        writeList("finance/budgets.json", "budgets", budgets);
    }

    private void persistDebts() {
        writeList("finance/debts.json", "debts", debts);
    }

    private void persistFingerprints() {
        writeList("finance/fingerprints.json", "fingerprints", fingerprints.stream().sorted().toList());
    }

    private void seedDefaultCategories() {
        for (String name : List.of("Ăn uống", "Di chuyển", "Nhà cửa", "Giải trí", "Sức khỏe", "Khác")) {
            categories.add(new Category(name, TxType.EXPENSE));
        }
        for (String name : List.of("Lương", "Thưởng", "Đầu tư", "Khác")) {
            categories.add(new Category(name, TxType.INCOME));
        }
        persistCategories();
    }

    public List<Category> categories(TxType type) {
        return categories.stream()
                .filter(c -> !c.archived && (type == null || c.type == type))
                .sorted(Comparator.comparing(c -> c.name))
                .toList();
    }

    public Category categoryById(String id) {
        return categories.stream().filter(c -> c.id.equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy danh mục: " + id));
    }

    public Category addCategory(String name, TxType type) {
        final String trimmed = Validators.requireNonBlank(name, "Tên danh mục");
        boolean exists = categories.stream()
                .anyMatch(c -> c.name.equalsIgnoreCase(trimmed) && c.type == type && !c.archived);
        if (exists) {
            throw new ValidationException("Danh mục đã tồn tại: " + trimmed);
        }
        Category c = new Category(trimmed, type);
        categories.add(c);
        persistCategories();
        change.fire();
        return c;
    }

    // ------------------------------------------------------------------ transactions

    private List<Transaction> year(int year) {
        return txByYear.computeIfAbsent(year, y -> {
            Type listType = new TypeToken<List<Transaction>>() {
            }.getType();
            return new ArrayList<>(readList("finance/transactions-" + y + ".json", "transactions", listType));
        });
    }

    private void persistYear(int year) {
        writeList("finance/transactions-" + year + ".json", "transactions", year(year));
    }

    /** All transactions in [from, to]. */
    public List<Transaction> inRange(LocalDate from, LocalDate to) {
        List<Transaction> out = new ArrayList<>();
        for (int y = from.getYear(); y <= to.getYear(); y++) {
            year(y).stream()
                    .filter(t -> !t.date.isBefore(from) && !t.date.isAfter(to))
                    .forEach(out::add);
        }
        out.sort(Comparator.comparing((Transaction t) -> t.date).thenComparing(t -> t.createdAt));
        return out;
    }

    public List<Transaction> all() {
        List<Transaction> out = new ArrayList<>();
        for (Integer y : discoverYears()) {
            out.addAll(year(y));
        }
        out.sort(Comparator.comparing((Transaction t) -> t.date).thenComparing(t -> t.createdAt));
        return out;
    }

    /** Years that exist on disk plus the current year. */
    private List<Integer> discoverYears() {
        java.util.TreeSet<Integer> years = new java.util.TreeSet<>(txByYear.keySet());
        Path dir = store.resolve("finance");
        try (var files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("transactions-") && n.endsWith(".json"))
                    .forEach(n -> {
                        try {
                            years.add(Integer.parseInt(n.substring("transactions-".length(), n.length() - ".json".length())));
                        } catch (NumberFormatException ignored) {
                            // not a transactions partition file
                        }
                    });
        } catch (IOException e) {
            LOG.warn("Could not list finance dir for year discovery: {}", e.toString());
        }
        years.add(LocalDate.now().getYear());
        return List.copyOf(years);
    }

    /** Stable fingerprint for duplicate detection on import. */
    public static String txFingerprint(LocalDate date, TxType type, BigDecimal amount, String description, String externalId) {
        String normalized = amount.stripTrailingZeros().toPlainString();
        return Fingerprint.sha256(date + "|" + type + "|" + normalized + "|"
                + (description == null ? "" : description.trim().toLowerCase()) + "|"
                + (externalId == null ? "" : externalId.trim()));
    }

    /**
     * Add a transaction with validation. Throws {@link ValidationException} on
     * invalid amount/category or when an identical transaction already exists
     * (duplicate protection) unless {@code allowDuplicate} is set.
     */
    public Transaction addTransaction(LocalDate date, TxType type, BigDecimal amount, String categoryId,
                                      PaymentMethod method, String description, Set<String> tags,
                                      String externalId, boolean allowDuplicate) {
        Validators.requireNonNull(date, "Ngày giao dịch");
        Validators.requireNonNull(type, "Loại giao dịch");
        Validators.requireNonNull(method, "Phương thức thanh toán");
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("Số tiền phải là một số dương");
        }
        Category category = categoryById(categoryId);
        if (category.type != type) {
            throw new ValidationException("Danh mục '" + category.name + "' không khớp loại giao dịch " + type);
        }
        String fp = txFingerprint(date, type, amount, description, externalId);
        if (!allowDuplicate && fingerprints.contains(fp)) {
            throw new ValidationException("Giao dịch trùng lặp đã tồn tại (ngày " + date + ", số tiền " + amount.toPlainString() + ")");
        }
        Transaction t = new Transaction();
        t.date = date;
        t.type = type;
        t.amount = amount;
        t.categoryId = categoryId;
        t.method = method;
        t.description = description == null ? "" : description.trim();
        if (tags != null) {
            tags.stream().filter(s -> !s.isBlank()).map(String::trim).forEach(t.tags::add);
        }
        t.externalId = externalId == null ? "" : externalId;
        t.importFingerprint = fp;
        year(date.getYear()).add(t);
        fingerprints.add(fp);
        persistYear(date.getYear());
        persistFingerprints();
        change.fire();
        return t;
    }

    public void updateTransaction(Transaction updated) {
        if (updated.amount == null || updated.amount.signum() <= 0) {
            throw new ValidationException("Số tiền phải là một số dương");
        }
        categoryById(updated.categoryId);
        List<Transaction> oldYear = year(updated.date.getYear());
        for (int i = 0; i < oldYear.size(); i++) {
            if (oldYear.get(i).id.equals(updated.id)) {
                oldYear.set(i, updated);
                persistYear(updated.date.getYear());
                change.fire();
                return;
            }
        }
        throw new NotFoundException("Không tìm thấy giao dịch: " + updated.id);
    }

    public void deleteTransaction(String id) {
        for (Map.Entry<Integer, List<Transaction>> e : txByYear.entrySet()) {
            if (e.getValue().removeIf(t -> t.id.equals(id))) {
                persistYear(e.getKey());
                change.fire();
                return;
            }
        }
        throw new NotFoundException("Không tìm thấy giao dịch: " + id);
    }

    // ------------------------------------------------------------------ reporting

    public record MonthSummary(YearMonth month, BigDecimal income, BigDecimal expense, BigDecimal net) {
    }

    public MonthSummary monthSummary(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (Transaction t : inRange(from, to)) {
            if (t.type == TxType.INCOME) {
                income = income.add(t.amount);
            } else {
                expense = expense.add(t.amount);
            }
        }
        return new MonthSummary(month, income, expense, income.subtract(expense));
    }

    /** Totals per category in range (only the given type). */
    public Map<String, BigDecimal> categoryTotals(LocalDate from, LocalDate to, TxType type) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Transaction t : inRange(from, to)) {
            if (t.type == type) {
                out.merge(t.categoryId, t.amount, BigDecimal::add);
            }
        }
        return out;
    }

    /** Daily income/expense series between two dates. */
    public List<DayPoint> dailySeries(LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> income = new HashMap<>();
        Map<LocalDate, BigDecimal> expense = new HashMap<>();
        for (Transaction t : inRange(from, to)) {
            if (t.type == TxType.INCOME) {
                income.merge(t.date, t.amount, BigDecimal::add);
            } else {
                expense.merge(t.date, t.amount, BigDecimal::add);
            }
        }
        List<DayPoint> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(new DayPoint(d, income.getOrDefault(d, BigDecimal.ZERO), expense.getOrDefault(d, BigDecimal.ZERO)));
        }
        return out;
    }

    public record DayPoint(LocalDate date, BigDecimal income, BigDecimal expense) {
    }

    // ------------------------------------------------------------------ budgets

    public record BudgetStatus(Budget budget, String categoryName, BigDecimal spent, BigDecimal remaining, double usedRatio) {
    }

    public List<Budget> budgetsFor(YearMonth month) {
        return budgets.stream().filter(b -> b.month.equals(month)).toList();
    }

    public Budget addBudget(YearMonth month, String categoryId, BigDecimal limit) {
        if (limit == null || limit.signum() < 0) {
            throw new ValidationException("Hạn mức ngân sách không hợp lệ");
        }
        categoryById(categoryId);
        Optional<Budget> existing = budgets.stream()
                .filter(b -> b.month.equals(month) && b.categoryId.equals(categoryId)).findFirst();
        if (existing.isPresent()) {
            existing.get().limitAmount = limit;
            persistBudgets();
            change.fire();
            return existing.get();
        }
        Budget b = new Budget(month, categoryId, limit);
        budgets.add(b);
        persistBudgets();
        change.fire();
        return b;
    }

    public void deleteBudget(String id) {
        if (!budgets.removeIf(b -> b.id.equals(id))) {
            throw new NotFoundException("Không tìm thấy ngân sách: " + id);
        }
        persistBudgets();
        change.fire();
    }

    public List<BudgetStatus> budgetStatus(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        Map<String, BigDecimal> spent = categoryTotals(from, to, TxType.EXPENSE);
        List<BudgetStatus> out = new ArrayList<>();
        for (Budget b : budgetsFor(month)) {
            BigDecimal s = spent.getOrDefault(b.categoryId, BigDecimal.ZERO);
            BigDecimal remaining = b.limitAmount.subtract(s);
            double ratio = b.limitAmount.signum() == 0 ? (s.signum() == 0 ? 0 : 2.0)
                    : s.divide(b.limitAmount, 4, java.math.RoundingMode.HALF_UP).doubleValue();
            out.add(new BudgetStatus(b, categoryById(b.categoryId).name, s, remaining, ratio));
        }
        return out;
    }

    // ------------------------------------------------------------------ debts

    public List<Debt> debts(DebtDirection direction, DebtStatus status) {
        return debts.stream()
                .filter(d -> direction == null || d.direction == direction)
                .filter(d -> status == null || d.status == status)
                .sorted(Comparator.comparing(d -> d.dueDate == null ? LocalDate.MAX : d.dueDate))
                .toList();
    }

    public Debt addDebt(DebtDirection direction, String party, BigDecimal amount,
                        LocalDate incurred, LocalDate due, String note) {
        Validators.requireNonNull(direction, "Loại khoản nợ");
        Validators.requireNonNull(amount, "Số tiền");
        if (amount.signum() <= 0) {
            throw new ValidationException("Số tiền phải là một số dương");
        }
        Debt d = new Debt();
        d.direction = direction;
        d.party = Validators.requireNonBlank(party, "Đối tượng");
        d.amount = amount;
        d.incurredDate = incurred;
        d.dueDate = due;
        d.note = note == null ? "" : note;
        debts.add(d);
        persistDebts();
        change.fire();
        return d;
    }

    public void settleDebt(String id) {
        Debt d = debts.stream().filter(x -> x.id.equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy khoản nợ: " + id));
        d.status = DebtStatus.SETTLED;
        d.settledAt = java.time.Instant.now();
        persistDebts();
        change.fire();
    }

    public void deleteDebt(String id) {
        if (!debts.removeIf(d -> d.id.equals(id))) {
            throw new NotFoundException("Không tìm thấy khoản nợ: " + id);
        }
        persistDebts();
        change.fire();
    }

    // ------------------------------------------------------------------ CSV

    private static final List<String> CSV_HEADER = List.of(
            "id", "date", "type", "category", "amount", "currency", "method", "description", "tags", "externalId");

    public Path exportCsv(Path target, LocalDate from, LocalDate to) {
        List<String> lines = new ArrayList<>();
        lines.add(String.join(",", CSV_HEADER));
        for (Transaction t : inRange(from, to)) {
            String catName = safeCategoryName(t.categoryId);
            lines.add(String.join(",",
                    CsvUtil.escape(t.id),
                    CsvUtil.escape(t.date.format(CSV_DATE)),
                    CsvUtil.escape(t.type.name()),
                    CsvUtil.escape(catName),
                    CsvUtil.escape(t.amount.toPlainString()),
                    CsvUtil.escape(t.currency),
                    CsvUtil.escape(t.method.name()),
                    CsvUtil.escape(t.description),
                    CsvUtil.escape(String.join(";", t.tags)),
                    CsvUtil.escape(t.externalId)));
        }
        try {
            CsvUtil.write(target, lines);
        } catch (IOException e) {
            throw new com.lifedesk.common.StorageException("Không ghi được file CSV: " + e.getMessage(), e);
        }
        return target;
    }

    private String safeCategoryName(String id) {
        return categories.stream().filter(c -> c.id.equals(id)).findFirst().map(c -> c.name).orElse("Khác");
    }

    /**
     * Import transactions from a CSV exported by LifeDesk (or matching its format).
     * Duplicate rows (same fingerprint already stored, or seen earlier in this file)
     * are skipped, never double-booked.
     */
    public CsvImportReport importCsv(Path csv) {
        CsvImportReport report = new CsvImportReport();
        List<String[]> rows;
        try {
            rows = CsvUtil.read(csv);
        } catch (IOException e) {
            throw new com.lifedesk.common.StorageException("Không đọc được file CSV: " + e.getMessage(), e);
        }
        if (rows.isEmpty()) {
            report.messages.add("File rỗng");
            return report;
        }
        String[] header = rows.get(0);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            col.put(header[i].trim().toLowerCase(), i);
        }
        if (!col.containsKey("date") || !col.containsKey("type") || !col.containsKey("amount")) {
            report.addError(1, "Thiếu cột bắt buộc (date, type, amount)");
            return report;
        }
        int startRow = col.containsKey("id") && col.containsKey("category") ? 1 : 0;
        Set<String> seenInFile = new HashSet<>();
        for (int r = startRow; r < rows.size(); r++) {
            String[] row = rows.get(r);
            int lineNo = r + 1;
            try {
                LocalDate date = LocalDate.parse(get(col, row, "date").trim(), CSV_DATE);
                TxType type = TxType.valueOf(get(col, row, "type").trim().toUpperCase());
                BigDecimal amount = new BigDecimal(get(col, row, "amount").trim());
                String desc = col.containsKey("description") ? get(col, row, "description") : "";
                String extId = col.containsKey("externalid") ? get(col, row, "externalid") : "";
                String catName = col.containsKey("category") ? get(col, row, "category").trim() : "";
                PaymentMethod method = col.containsKey("method")
                        ? PaymentMethod.valueOf(get(col, row, "method").trim().toUpperCase())
                        : PaymentMethod.CASH;
                Set<String> tags = new LinkedHashSet<>();
                if (col.containsKey("tags") && !get(col, row, "tags").isBlank()) {
                    for (String s : get(col, row, "tags").split(";")) {
                        if (!s.isBlank()) {
                            tags.add(s.trim());
                        }
                    }
                }

                String fp = txFingerprint(date, type, amount, desc, extId);
                if (fingerprints.contains(fp) || !seenInFile.add(fp)) {
                    report.skippedDuplicates++;
                    continue;
                }

                String categoryId = resolveCategory(catName, type);
                addTransaction(date, type, amount, categoryId, method, desc, tags, extId, false);
                report.created++;
            } catch (DateTimeParseException e) {
                report.addError(lineNo, "Ngày không hợp lệ: " + e.getParsedString());
            } catch (IllegalArgumentException e) {
                report.addError(lineNo, "Giá trị không hợp lệ: " + e.getMessage());
            } catch (Exception e) {
                report.addError(lineNo, e.getMessage());
            }
        }
        report.messages.add("Đã nhập " + report.created + " giao dịch, bỏ qua " + report.skippedDuplicates + " dòng trùng.");
        LOG.info("CSV import: created={}, duplicates={}, errors={}", report.created, report.skippedDuplicates, report.errors);
        return report;
    }

    private String resolveCategory(String name, TxType type) {
        if (name == null || name.isBlank()) {
            return categories.stream().filter(c -> c.type == type).findFirst()
                    .orElseThrow(() -> new ValidationException("Không có danh mục " + type))
                    .id;
        }
        Optional<Category> existing = categories.stream()
                .filter(c -> c.name.equalsIgnoreCase(name) && c.type == type).findFirst();
        if (existing.isPresent()) {
            return existing.get().id;
        }
        return addCategory(name, type).id;
    }

    private String get(Map<String, Integer> col, String[] row, String key) {
        Integer idx = col.get(key);
        if (idx == null || idx >= row.length) {
            return "";
        }
        return row[idx];
    }

    public int transactionCount() {
        int total = 0;
        for (Integer y : discoverYears()) {
            total += year(y).size();
        }
        return total;
    }
}
