package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.finance.Budget;
import com.lifedesk.finance.Category;
import com.lifedesk.finance.CsvImportReport;
import com.lifedesk.finance.Debt;
import com.lifedesk.finance.DebtDirection;
import com.lifedesk.finance.DebtStatus;
import com.lifedesk.finance.FinanceService;
import com.lifedesk.finance.PaymentMethod;
import com.lifedesk.finance.Transaction;
import com.lifedesk.finance.TxType;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.IconView;
import com.lifedesk.ui.editors.FinanceDialogs;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Personal finance: always-visible quick entry (income/expense), month
 * navigation with summary cards, budgets, debts and reports + CSV.
 * Editor dialogs live in {@link FinanceDialogs}; this class owns layout,
 * tables and the quick-add bar.
 */
public class FinanceView extends View {
    private static final NumberFormat VND = NumberFormat.getNumberInstance(new Locale("vi", "VN"));
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MM/yyyy");

    // month state + navigation
    private YearMonth selMonth = YearMonth.now();
    private Label monthLabel;

    // quick-add controls
    private final ToggleButton quickExpense = new ToggleButton("Chi tiêu");
    private final ToggleButton quickIncome = new ToggleButton("Thu nhập");
    private final TextField quickAmount = new TextField();
    private final ComboBox<Category> quickCategory = new ComboBox<>();
    private final ComboBox<PaymentMethod> quickMethod =
            new ComboBox<>(FXCollections.observableArrayList(PaymentMethod.values()));
    private final DatePicker quickDate = new DatePicker(LocalDate.now());
    private final TextField quickDesc = new TextField();

    // transactions tab
    private final TableView<Transaction> txTable = new TableView<>();
    private final TextField txSearch = new TextField();
    private final ComboBox<String> txTypeFilter = new ComboBox<>();
    private final Label incomeMetric = metric();
    private final Label expenseMetric = metric();
    private final Label netMetric = metric();
    private final Label savingMetric = metric();

    private final TableView<Budget> budgetTable = new TableView<>();
    private final TableView<Debt> debtTable = new TableView<>();
    /**
     * Budget status (spent/remaining) per budget id, computed once per refresh.
     * Cell factories only read this map — recomputing categoryTotals per painted
     * cell made every table repaint O(rows × transactions).
     */
    private Map<String, FinanceService.BudgetStatus> budgetStatusById = Map.of();

    public FinanceView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "FINANCE";
    }

    private static Label metric() {
        Label l = new Label("0 đ");
        l.getStyleClass().add("metric-big");
        return l;
    }

    @Override
    protected Node build() {
        // ---- header
        Label title = new Label("Tài chính cá nhân");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button exportBtn = new Button("Xuất CSV");
        exportBtn.setOnAction(e -> exportCsv());
        Button importBtn = new Button("Nhập CSV");
        importBtn.setOnAction(e -> importCsv());
        Button catBtn = new Button("Danh mục…");
        catBtn.setTooltip(new Tooltip("Thêm / đổi tên / xóa danh mục"));
        catBtn.setOnAction(e -> FinanceDialogs.manageCategories(ctx, this::afterCategoryChange));
        HBox header = new HBox(10, title, spacer, catBtn, exportBtn, importBtn);
        header.setAlignment(Pos.CENTER_LEFT);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("Giao dịch", buildTransactionsTab()),
                new Tab("Ngân sách", buildBudgetsTab()),
                new Tab("Khoản phải thu / phải trả", buildDebtsTab()),
                new Tab("Báo cáo", buildReportsTab()));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        ctx.finance().changes().addListener(this::scheduleRefresh);
        return new VBox(12, header, tabs);
    }

    private void afterCategoryChange() {
        reloadQuickCategories();
        refresh();
    }

    // ------------------------------------------------------------------ transactions tab

    private Node buildTransactionsTab() {
        // ---- month navigator
        Button prevMonth = new Button("", new IconView("chevron-left", 15));
        prevMonth.getStyleClass().add("icon-btn");
        prevMonth.setTooltip(new Tooltip("Tháng trước"));
        prevMonth.setOnAction(e -> {
            selMonth = selMonth.minusMonths(1);
            refresh();
        });
        Button nextMonth = new Button("", new IconView("chevron-right", 15));
        nextMonth.getStyleClass().add("icon-btn");
        nextMonth.setTooltip(new Tooltip("Tháng sau"));
        nextMonth.setOnAction(e -> {
            selMonth = selMonth.plusMonths(1);
            refresh();
        });
        Button thisMonth = new Button("Tháng này");
        thisMonth.setOnAction(e -> {
            selMonth = YearMonth.now();
            refresh();
        });
        monthLabel = new Label();
        monthLabel.getStyleClass().add("card-headline");
        HBox monthNav = new HBox(8, prevMonth, monthLabel, nextMonth, thisMonth);
        monthNav.setAlignment(Pos.CENTER_LEFT);

        // ---- summary cards
        HBox summary = new HBox(12,
                summaryCard("THU NHẬP", incomeMetric, "metric-pos"),
                summaryCard("CHI TIÊU", expenseMetric, "metric-neg"),
                summaryCard("RÒNG", netMetric, "metric-pos"),
                summaryCard("TIẾT KIỆM", savingMetric, "metric-pos"));
        summary.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        // ---- quick-add bar: nhập thu/chi trực tiếp
        ToggleGroup typeGroup = new ToggleGroup();
        quickExpense.setToggleGroup(typeGroup);
        quickIncome.setToggleGroup(typeGroup);
        quickExpense.setSelected(true);
        quickExpense.getStyleClass().add("toggle-button");
        quickIncome.getStyleClass().add("toggle-button");
        typeGroup.selectedToggleProperty().addListener((o, a, b) -> reloadQuickCategories());

        quickAmount.setPromptText("Số tiền (VD 250000)");
        quickAmount.setPrefWidth(170);
        quickAmount.setOnAction(e -> quickAdd());
        quickCategory.setPrefWidth(170);
        quickMethod.setValue(PaymentMethod.CASH);
        quickMethod.setPrefWidth(150);
        quickDate.setPrefWidth(135);
        quickDesc.setPromptText("Mô tả ngắn (Enter để lưu)…");
        HBox.setHgrow(quickDesc, Priority.ALWAYS);
        quickDesc.setOnAction(e -> quickAdd());

        Button addBtn = new Button("Thêm giao dịch", new IconView("plus", 15));
        addBtn.getStyleClass().add("primary-btn");
        addBtn.setOnAction(e -> quickAdd());
        addAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), addBtn::fire);
        addBtn.setTooltip(new Tooltip("Lưu giao dịch ngay (Enter ở ô Số tiền hoặc Mô tả cũng lưu)"));

        Button addCatBtn = new Button("Danh mục", new IconView("plus", 15));
        addCatBtn.setOnAction(e -> quickAddCategory());

        javafx.scene.layout.FlowPane quickBar = new javafx.scene.layout.FlowPane(10, 8,
                quickExpense, quickIncome, quickAmount, quickCategory,
                quickMethod, quickDate, quickDesc, addCatBtn, addBtn);
        quickBar.getStyleClass().add("quick-add-bar");
        quickDesc.setPrefWidth(320);

        // ---- search/filter/table
        txSearch.setPromptText("Tìm trong tháng (mô tả, tag)…");
        txSearch.textProperty().addListener((o, a, b) -> refreshTransactions());
        txTypeFilter.getItems().addAll("(tất cả)", "INCOME", "EXPENSE");
        txTypeFilter.setValue("(tất cả)");
        txTypeFilter.setOnAction(e -> refreshTransactions());
        Button editTx = new Button("Sửa", new IconView("pencil", 15));
        editTx.setOnAction(e -> {
            Transaction t = txTable.getSelectionModel().getSelectedItem();
            if (t != null) {
                FinanceDialogs.editTransaction(ctx, t, this::refresh);
            }
        });
        Button delTx = new Button("Xóa");
        delTx.getStyleClass().add("danger-btn");
        delTx.setOnAction(e -> deleteSelectedTransaction());

        HBox toolbar = new HBox(8, txSearch, new Label("Loại:"), txTypeFilter, editTx, delTx);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(txSearch, Priority.ALWAYS);

        buildTxTable();
        txTable.setPlaceholder(new EmptyState("coins",
                "Chưa có giao dịch trong tháng này.\nNhập thu/chi bằng thanh bên trên — Enter là lưu."));

        reloadQuickCategories();
        return new VBox(12, monthNav, summary, quickBar, toolbar, txTable);
    }

    private Node summaryCard(String caption, Label metric, String metricClass) {
        Label cap = new Label(caption);
        cap.getStyleClass().add("overline");
        metric.getStyleClass().add(metricClass);
        VBox card = new VBox(2, cap, metric);
        card.getStyleClass().add("summary-card");
        return card;
    }

    private void buildTxTable() {
        TableColumn<Transaction, String> dateCol = new TableColumn<>("Ngày");
        dateCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().date.format(DateTimeFormatter.ofPattern("dd/MM"))));
        dateCol.setPrefWidth(70);

        TableColumn<Transaction, Transaction> typeCol = new TableColumn<>("Loại");
        typeCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        typeCol.setCellFactory(c -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Transaction item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                setGraphic(empty || item == null ? null
                        : com.lifedesk.ui.components.Chips.chip(
                        item.type == TxType.INCOME ? "THU" : "CHI",
                        item.type == TxType.INCOME ? "chip-done" : "chip-overdue"));
            }
        });
        typeCol.setPrefWidth(70);

        TableColumn<Transaction, String> amountCol = new TableColumn<>("Số tiền");
        amountCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                (p.getValue().type == TxType.INCOME ? "+ " : "− ") + VND.format(p.getValue().amount) + " đ"));
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT; -fx-font-weight: bold;");
        amountCol.setPrefWidth(130);

        TableColumn<Transaction, String> catCol = new TableColumn<>("Danh mục");
        catCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(safeCategory(p.getValue())));
        catCol.setPrefWidth(130);

        TableColumn<Transaction, String> methodCol = new TableColumn<>("Thanh toán");
        methodCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(methodLabel(p.getValue().method)));
        methodCol.setPrefWidth(120);

        TableColumn<Transaction, String> descCol = new TableColumn<>("Mô tả");
        descCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().description));

        TableColumn<Transaction, String> tagsCol = new TableColumn<>("Tags");
        tagsCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(String.join(", ", p.getValue().tags)));

        txTable.getColumns().setAll(List.of(dateCol, typeCol, amountCol, catCol, methodCol, descCol, tagsCol));
        txTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        txTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && txTable.getSelectionModel().getSelectedItem() != null) {
                FinanceDialogs.editTransaction(ctx, txTable.getSelectionModel().getSelectedItem(), this::refresh);
            }
        });
        VBox.setVgrow(txTable, Priority.ALWAYS);
    }

    private String safeCategory(Transaction t) {
        try {
            return ctx.finance().categoryById(t.categoryId).name;
        } catch (RuntimeException e) {
            return "Khác";
        }
    }

    private static String methodLabel(PaymentMethod m) {
        return switch (m) {
            case CASH -> "Tiền mặt";
            case BANK_CARD -> "Thẻ ngân hàng";
            case BANK_TRANSFER -> "Chuyển khoản";
            case E_WALLET -> "Ví điện tử";
            case OTHER -> "Khác";
        };
    }

    /** Quick add: validate + save in one action (Enter or button). */
    private void quickAdd() {
        try {
            String raw = quickAmount.getText().trim().replace(',', '.');
            if (raw.isEmpty()) {
                main.status("Nhập số tiền trước đã.");
                quickAmount.requestFocus();
                return;
            }
            BigDecimal value = new BigDecimal(raw);
            Category cat = quickCategory.getValue();
            if (cat == null) {
                Dialogs.info("Chọn danh mục", "Hãy chọn (hoặc tạo) một danh mục cho giao dịch.");
                return;
            }
            TxType type = quickIncome.isSelected() ? TxType.INCOME : TxType.EXPENSE;
            LocalDate date = quickDate.getValue() == null ? LocalDate.now() : quickDate.getValue();
            ctx.finance().addTransaction(date, type, value, cat.id, quickMethod.getValue(),
                    quickDesc.getText(), null, null, false);
            main.status("Đã lưu: " + (type == TxType.INCOME ? "thu " : "chi ")
                    + VND.format(value) + " đ — " + quickDesc.getText());
            quickAmount.clear();
            quickDesc.clear();
            quickAmount.requestFocus();
            refresh();
        } catch (NumberFormatException e) {
            Dialogs.error("Số tiền không hợp lệ", "Chỉ nhập số, ví dụ: 250000 hoặc 1.5 (triệu không hỗ trợ — nhập 1500000).");
        } catch (RuntimeException e) {
            Dialogs.error("Không lưu được giao dịch", e.getMessage());
        }
    }

    /** Create a category inline for the type currently selected in the quick-add bar. */
    private void quickAddCategory() {
        TxType type = quickIncome.isSelected() ? TxType.INCOME : TxType.EXPENSE;
        String name = Dialogs.prompt("Danh mục mới",
                "Tên danh mục " + (type == TxType.INCOME ? "thu nhập" : "chi tiêu") + ":", "").orElse(null);
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            Category c = ctx.finance().addCategory(name, type);
            reloadQuickCategories();
            quickCategory.getSelectionModel().select(c);
        } catch (RuntimeException e) {
            Dialogs.error("Không tạo được danh mục", e.getMessage());
        }
    }

    private void reloadQuickCategories() {
        TxType type = quickIncome.isSelected() ? TxType.INCOME : TxType.EXPENSE;
        Category selected = quickCategory.getValue();
        quickCategory.getItems().setAll(ctx.finance().categories(type));
        if (selected != null && quickCategory.getItems().contains(selected)) {
            quickCategory.getSelectionModel().select(selected);
        } else {
            quickCategory.getSelectionModel().selectFirst();
        }
    }

    private void refreshTransactions() {
        LocalDate from = selMonth.atDay(1);
        LocalDate to = selMonth.atEndOfMonth();
        monthLabel.setText("Tháng " + selMonth.format(MONTH_FMT));

        String q = txSearch.getText();
        String typeFilter = txTypeFilter.getValue();
        var list = ctx.finance().inRange(from, to).stream()
                .filter(t -> typeFilter == null || "(tất cả)".equals(typeFilter) || t.type.name().equals(typeFilter))
                .filter(t -> q == null || q.isBlank()
                        || t.description.toLowerCase().contains(q.toLowerCase())
                        || t.tags.stream().anyMatch(tag -> tag.toLowerCase().contains(q.toLowerCase())))
                .toList();
        txTable.getItems().setAll(list);

        var summary = ctx.finance().monthSummary(selMonth);
        incomeMetric.setText(VND.format(summary.income()) + " đ");
        expenseMetric.setText(VND.format(summary.expense()) + " đ");
        netMetric.setText(VND.format(summary.net()) + " đ");
        netMetric.getStyleClass().removeAll("metric-pos", "metric-neg");
        netMetric.getStyleClass().add(summary.net().signum() >= 0 ? "metric-pos" : "metric-neg");
        if (summary.income().signum() > 0) {
            double rate = summary.net().divide(summary.income(), 4, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
            savingMetric.setText(String.format(Locale.ROOT, "%.0f%%", rate));
            savingMetric.getStyleClass().removeAll("metric-pos", "metric-neg");
            savingMetric.getStyleClass().add(rate >= 0 ? "metric-pos" : "metric-neg");
        } else {
            savingMetric.setText("—");
        }
        reloadQuickCategories();
    }

    private void deleteSelectedTransaction() {
        Transaction t = txTable.getSelectionModel().getSelectedItem();
        if (t != null && Dialogs.confirm("Xóa giao dịch",
                "Xóa giao dịch " + VND.format(t.amount) + " đ ngày " + t.date + "?")) {
            ctx.finance().deleteTransaction(t.id);
        }
    }

    // ------------------------------------------------------------------ budgets

    private Node buildBudgetsTab() {
        buildBudgetTable();
        Button addBudget = new Button("Đặt ngân sách");
        addBudget.getStyleClass().add("primary-btn");
        addBudget.setOnAction(e -> FinanceDialogs.editBudget(ctx, null, this::refresh));
        Button editBudget = new Button("Sửa");
        editBudget.setOnAction(e -> {
            Budget b = budgetTable.getSelectionModel().getSelectedItem();
            if (b != null) {
                FinanceDialogs.editBudget(ctx, b, this::refresh);
            }
        });
        Button delBudget = new Button("Xóa");
        delBudget.setOnAction(e -> {
            Budget b = budgetTable.getSelectionModel().getSelectedItem();
            if (b != null && Dialogs.confirm("Xóa ngân sách", "Xóa ngân sách tháng " + b.month + "?")) {
                ctx.finance().deleteBudget(b.id);
            }
        });
        HBox toolbar = new HBox(8, addBudget, editBudget, delBudget);
        return new VBox(12, toolbar, budgetTable);
    }

    private void buildBudgetTable() {
        TableColumn<Budget, String> monthCol = new TableColumn<>("Tháng");
        monthCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().recurring
                ? "lặp hàng tháng" : p.getValue().month.toString()));
        TableColumn<Budget, String> catCol = new TableColumn<>("Danh mục");
        catCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(safeBudgetCategory(p.getValue())));
        TableColumn<Budget, String> limitCol = new TableColumn<>("Hạn mức");
        limitCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(VND.format(p.getValue().limitAmount) + " đ"));
        TableColumn<Budget, String> spentCol = new TableColumn<>("Đã chi");
        spentCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(VND.format(spentOf(p.getValue())) + " đ"));
        TableColumn<Budget, String> remainCol = new TableColumn<>("Còn lại");
        remainCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(VND.format(remainingOf(p.getValue())) + " đ"));
        budgetTable.getColumns().setAll(List.of(monthCol, catCol, limitCol, spentCol, remainCol));
        budgetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private FinanceService.BudgetStatus statusOf(Budget b) {
        return budgetStatusById.get(b.id);
    }

    private BigDecimal spentOf(Budget b) {
        FinanceService.BudgetStatus s = statusOf(b);
        return s == null ? BigDecimal.ZERO : s.spent();
    }

    private BigDecimal remainingOf(Budget b) {
        FinanceService.BudgetStatus s = statusOf(b);
        return s == null ? BigDecimal.ZERO : s.remaining();
    }

    private String safeBudgetCategory(Budget b) {
        try {
            return ctx.finance().categoryById(b.categoryId).name;
        } catch (RuntimeException e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ debts

    private Node buildDebtsTab() {
        buildDebtTable();
        Button addDebt = new Button("Thêm khoản");
        addDebt.getStyleClass().add("primary-btn");
        addDebt.setOnAction(e -> FinanceDialogs.editDebt(ctx, null, this::refresh));
        Button editDebt = new Button("Sửa");
        editDebt.setOnAction(e -> {
            Debt d = debtTable.getSelectionModel().getSelectedItem();
            if (d != null) {
                FinanceDialogs.editDebt(ctx, d, this::refresh);
            }
        });
        Button settle = new Button("Tất toán");
        settle.setOnAction(e -> {
            Debt d = debtTable.getSelectionModel().getSelectedItem();
            if (d != null) {
                ctx.finance().settleDebt(d.id);
            }
        });
        Button delDebt = new Button("Xóa");
        delDebt.setOnAction(e -> {
            Debt d = debtTable.getSelectionModel().getSelectedItem();
            if (d != null && Dialogs.confirm("Xóa khoản", "Xóa khoản \"" + d.party + "\"?")) {
                ctx.finance().deleteDebt(d.id);
            }
        });
        HBox toolbar = new HBox(8, addDebt, editDebt, settle, delDebt);
        return new VBox(12, toolbar, debtTable);
    }

    private void buildDebtTable() {
        TableColumn<Debt, String> dirCol = new TableColumn<>("Loại");
        dirCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().direction == DebtDirection.RECEIVABLE ? "Phải thu (nợ mình)" : "Phải trả (mình nợ)"));
        TableColumn<Debt, String> partyCol = new TableColumn<>("Đối tượng");
        partyCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().party));
        TableColumn<Debt, String> amountCol = new TableColumn<>("Số tiền");
        amountCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(VND.format(p.getValue().amount) + " đ"));
        TableColumn<Debt, String> dueCol = new TableColumn<>("Hạn");
        dueCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().dueDate == null ? "—" : p.getValue().dueDate.toString()));
        TableColumn<Debt, String> statusCol = new TableColumn<>("Trạng thái");
        statusCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(
                p.getValue().status == DebtStatus.OPEN ? "Đang mở" : "Đã tất toán"));
        debtTable.getColumns().setAll(List.of(dirCol, partyCol, amountCol, dueCol, statusCol));
        debtTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    // ------------------------------------------------------------------ reports

    private Node buildReportsTab() {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel("VND");
        BarChart<String, Number> monthly = new BarChart<>(x, y);
        monthly.setTitle("Thu / chi 6 tháng gần nhất");
        monthly.setPrefHeight(300);
        monthly.setLegendVisible(true);

        var now = YearMonth.now();
        var incomeSeries = new javafx.scene.chart.XYChart.Series<String, Number>();
        incomeSeries.setName("Thu nhập");
        var expenseSeries = new javafx.scene.chart.XYChart.Series<String, Number>();
        expenseSeries.setName("Chi tiêu");
        for (int i = 5; i >= 0; i--) {
            YearMonth m = now.minusMonths(i);
            var s = ctx.finance().monthSummary(m);
            String label = m.format(MONTH_FMT);
            incomeSeries.getData().add(new javafx.scene.chart.XYChart.Data<>(label, s.income().doubleValue()));
            expenseSeries.getData().add(new javafx.scene.chart.XYChart.Data<>(label, s.expense().doubleValue()));
        }
        monthly.getData().addAll(incomeSeries, expenseSeries);

        PieChart categoryPie = new PieChart();
        categoryPie.setTitle("Chi tiêu theo danh mục (tháng này)");
        Map<String, BigDecimal> totals = ctx.finance().categoryTotals(
                now.atDay(1), now.atEndOfMonth(), TxType.EXPENSE);
        if (totals.isEmpty()) {
            categoryPie.getData().add(new PieChart.Data("Chưa có dữ liệu", 1));
        } else {
            totals.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(8)
                    .forEach(e -> categoryPie.getData().add(
                            new PieChart.Data(safeCategoryName(e.getKey()), e.getValue().doubleValue())));
        }

        HBox charts = new HBox(20, monthly, categoryPie);
        HBox.setHgrow(monthly, Priority.ALWAYS);
        return new VBox(12, charts);
    }

    private String safeCategoryName(String id) {
        try {
            return ctx.finance().categoryById(id).name;
        } catch (RuntimeException e) {
            return "Khác";
        }
    }

    // ------------------------------------------------------------------ CSV

    private void exportCsv() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Xuất CSV");
        chooser.setInitialFileName("lifedesk-finance-" + YearMonth.now() + ".csv");
        File file = chooser.showSaveDialog(main.stage());
        if (file == null) {
            return;
        }
        try {
            var path = ctx.finance().exportCsv(file.toPath(), LocalDate.now().minusYears(5), LocalDate.now());
            Dialogs.info("Xuất CSV thành công", "Đã ghi file: " + path);
        } catch (RuntimeException e) {
            Dialogs.error("Xuất CSV thất bại", e.getMessage());
        }
    }

    private void importCsv() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Nhập CSV");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showOpenDialog(main.stage());
        if (file == null) {
            return;
        }
        try {
            CsvImportReport report = ctx.finance().importCsv(file.toPath());
            Dialogs.info("Kết quả nhập CSV",
                    "Tạo mới: " + report.created + ", trùng bỏ qua: " + report.skippedDuplicates
                            + ", lỗi: " + report.errors + (report.errors > 0
                            ? "\n" + String.join("\n", report.errorLines) : ""));
        } catch (RuntimeException e) {
            Dialogs.error("Nhập CSV thất bại", e.getMessage());
        }
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    protected void refresh() {
        refreshTransactions();
        Map<String, FinanceService.BudgetStatus> statuses = new HashMap<>();
        for (FinanceService.BudgetStatus s : ctx.finance().budgetStatus(YearMonth.now())) {
            statuses.put(s.budget().id, s);
        }
        budgetStatusById = statuses;
        budgetTable.getItems().setAll(ctx.finance().budgetsFor(YearMonth.now()));
        debtTable.getItems().setAll(ctx.finance().debts(null, null));
    }

    @Override
    public void openRecord(String recordId) {
        ctx.finance().all().stream().filter(t -> t.id.equals(recordId)).findFirst()
                .ifPresent(t -> {
                    selMonth = YearMonth.from(t.date);
                    refresh();
                    txTable.getSelectionModel().select(t);
                });
    }
}
