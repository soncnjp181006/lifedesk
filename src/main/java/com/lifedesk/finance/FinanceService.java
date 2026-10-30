package com.lifedesk.finance;

import com.google.gson.reflect.TypeToken;
import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.common.CsvUtil;
import com.lifedesk.common.Fingerprint;
import com.lifedesk.common.NotFoundException;
import com.lifedesk.common.ValidationException;
import com.lifedesk.common.Validators;
import com.lifedesk.storage.JsonStore;
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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Personal finance business logic.
 *
 * Money is {@link BigDecimal} end to end — never double. Transactions are
 * partitioned into one JSON file per year (finance/transactions-YYYY.json)
 * so the store stays small and fast. CSV import de-duplicates re-imported
 * rows using a stable fingerprint (date|type|amount|description|externalId)
 * derived from the stored transactions themselves — no separate fingerprint
 * file to keep in sync. Nothing ever leaves the machine.
 */
public class FinanceService {
    private static final Logger LOG = LoggerFactory.getLogger(FinanceService.class);
    private static final DateTimeFormatter CSV_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Type TX_LIST = new TypeToken<List<Transaction>>() {
    }.getType();

    private final JsonStore store;
    private final ChangeNotifier change = new ChangeNotifier();

    private final Map<Integer, List<Transaction>> txByYear = new HashMap<>();
    private final List<Category> categories = new ArrayList<>();
    private final List<Budget> budgets = new ArrayList<>();
    private final List<Debt> debts = new ArrayList<>();
    /** Import fingerprints of currently stored transactions; derived lazily from rows. */
    private final Set<String> knownFingerprints = new HashSet<>();
    private boolean fingerprintsLoaded = false;

    public FinanceService(JsonStore store) {
        this.store = store;
        categories.addAll(store.readList("finance/categories.json", "categories",
                new TypeToken<List<Category>>() {
                }.getType()));
        budgets.addAll(store.readList("finance/budgets.json", "budgets",
                new TypeToken<List<Budget>>() {
                }.getType()));
        debts.addAll(store.readList("finance/debts.json", "debts",
                new TypeToken<List<Debt>>() {
                }.getType()));
        if (categories.isEmpty()) {
            seedDefaultCategories();
        }
    }

    public ChangeNotifier changes() {
        return change;
    }

    // ------------------------------------------------------------------ categories

    private void persistCategories() {
        store.writeList("finance/categories.json", "categories", 1, categories);
    }

    private void persistBudgets() {
        store.writeList("finance/budgets.json", "budgets", 1, budgets);
    }

    private void persistDebts() {
        store.writeList("finance/debts.json", "debts", 1, debts);
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

    public Category renameCategory(String id, String newName) {
        Category c = categoryById(id);
        String trimmed = Validators.requireNonBlank(newName, "Tên danh mục");
        boolean dup = categories.stream()
                .anyMatch(x -> !x.id.equals(id) && x.name.equalsIgnoreCase(trimmed) && x.type == c.type && !x.archived);
        if (dup) {
            throw new ValidationException("Đã tồn tại danh mục cùng tên: " + trimmed);
        }
        c.name = trimmed;
        persistCategories();
        change.fire();
        return c;
    }

    /** Delete a category; refused while transactions still reference it (data integrity). */
    public void deleteCategory(String id) {
        Category c = categoryById(id);
        boolean used = discoverYears().stream()
                .anyMatch(y -> year(y).stream().anyMatch(t -> t.categoryId.equals(id)));
        if (used) {
            throw new ValidationException("Danh mục '" + c.name + "' đang có giao dịch — không thể xóa. "
                    + "Hãy đổi tên nó thay vì xóa.");
        }
        if (budgets.removeIf(b -> b.categoryId.equals(id))) {
            persistBudgets();
        }
        categories.remove(c);
        persistCategories();
        change.fire();
    }

    // ------------------------------------------------------------------ transactions

    private List<Transaction> year(int year) {
        return txByYear.computeIfAbsent(year,
                y -> new ArrayList<>(store.readList("finance/transactions-" + y + ".json", "transactions", TX_LIST)));
    }

    private void persistYear(int year) {
        store.writeList("finance/transactions-" + year + ".json", "transactions", 1, year(year));
    }

    /**
     * Import fingerprints of the transactions currently in the store, derived
     * from the rows themselves on first use — a deleted transaction releases
     * its fingerprint automatically (re-import becomes possible again).
     */
    private Set<String> fingerprints() {
        if (!fingerprintsLoaded) {
            for (Integer y : discoverYears()) {
                for (Transaction t : year(y)) {
                    if (t.importFingerprint != null && !t.importFingerprint.isBlank()) {
                        knownFingerprints.add(t.importFingerprint);
                    }
                }
            }
            fingerprintsLoaded = true;
        }
        return knownFingerprints;
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
        if (!allowDuplicate && fingerprints().contains(fp)) {
            // Real-world rule: identical entries on DIFFERENT days are legitimate
            // (daily coffee, monthly rent) - only block a same-day double-entry.
            // CSV import still deduplicates against the full fingerprint set.
            boolean sameDay = year(date.getYear()).stream()
                    .anyMatch(t -> t.importFingerprint.equals(fp) && t.date.equals(date));
            if (sameDay) {
                throw new ValidationException("Giao dịch trùng lặp trong cùng ngày " + date
                        + " (" + amount.toPlainString() + ")");
            }
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
        fingerprints().add(fp);
        persistYear(date.getYear());
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
            Iterator<Transaction> it = e.getValue().iterator();
            while (it.hasNext()) {
                Transaction t = it.next();
                if (t.id.equals(id)) {
                    it.remove();
                    if (fingerprintsLoaded && t.importFingerprint != null) {
                        // Row gone → its fingerprint is releasable for re-import.
                        knownFingerprints.remove(t.importFingerprint);
                    }
                    persistYear(e.getKey());
                    change.fire();
                    return;
                }
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
        List<Budget> out = new ArrayList<>(budgets.stream().filter(b -> !b.recurring && b.month.equals(month)).toList());
        for (Budget rec : budgets.stream().filter(b -> b.recurring).toList()) {
            boolean overridden = out.stream().anyMatch(b -> b.categoryId.equals(rec.categoryId));
            if (!overridden) {
                Budget effective = new Budget(month, rec.categoryId, rec.limitAmount);
                effective.id = rec.id; // keep identity for edit/delete operations
                effective.recurring = true;
                out.add(effective);
            }
        }
        return out;
    }

    /**
     * Create or update a budget. With {@code recurring = true} the budget is a
     * monthly template that applies to every month (real-app pattern: budget
     * templates are separate from per-month overrides).
     */
    public Budget addBudget(YearMonth month, String categoryId, BigDecimal limit, boolean recurring) {
        if (limit == null || limit.signum() < 0) {
            throw new ValidationException("Hạn mức ngân sách không hợp lệ");
        }
        categoryById(categoryId);
        Optional<Budget> existing = budgets.stream()
                .filter(b -> b.recurring == recurring && b.categoryId.equals(categoryId)
                        && (recurring || b.month.equals(month)))
                .findFirst();
        if (existing.isPresent()) {
            existing.get().limitAmount = limit;
            persistBudgets();
            change.fire();
            return existing.get();
        }
        Budget b = new Budget(month, categoryId, limit);
        b.recurring = recurring;
        budgets.add(b);
        persistBudgets();
        change.fire();
        return b;
    }

    public Budget addBudget(YearMonth month, String categoryId, BigDecimal limit) {
        return addBudget(month, categoryId, limit, false);
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

    public void updateDebt(Debt updated) {
        Debt existing = debts.stream().filter(d -> d.id.equals(updated.id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Không tìm thấy khoản nợ: " + updated.id));
        if (updated.amount == null || updated.amount.signum() <= 0) {
            throw new ValidationException("Số tiền phải là một số dương");
        }
        existing.direction = updated.direction;
        existing.party = Validators.requireNonBlank(updated.party, "Đối tượng");
        existing.amount = updated.amount;
        existing.incurredDate = updated.incurredDate;
        existing.dueDate = updated.dueDate;
        existing.note = updated.note;
        persistDebts();
        change.fire();
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
                if (fingerprints().contains(fp) || !seenInFile.add(fp)) {
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
