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
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.File;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Personal finance: transactions, budgets, debts, reports, CSV import/export. */
public class FinanceView extends View {
    private static final NumberFormat VND = NumberFormat.getNumberInstance(new Locale("vi", "VN"));

    private final TableView<Transaction> txTable = new TableView<>();
    private final TextField txSearch = new TextField();
    private final ComboBox<String> txTypeFilter = new ComboBox<>();
    private final TableView<Budget> budgetTable = new TableView<>();
    private final TableView<Debt> debtTable = new TableView<>();
    private final ComboBox<Category> quickCategory = new ComboBox<>();
    private final Label monthIncome = bigMetric();
    private final Label monthExpense = bigMetric();
    private final Label monthNet = bigMetric();

    public FinanceView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "FINANCE";
    }

    private static Label bigMetric() {
        Label l = new Label("0");
        l.getStyleClass().add("metric-big");
        return l;
    }

    @Override
    protected Node build() {
        Label title = new Label("Tài chính cá nhân");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button exportBtn = new Button("⬇ Xuất CSV");
        exportBtn.setOnAction(e -> exportCsv());
        Button importBtn = new Button("⬆ Nhập CSV");
        importBtn.setOnAction(e -> importCsv());
        HBox header = new HBox(10, title, spacer, exportBtn, importBtn);
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
        return new VBox(10, header, tabs);
    }

    private volatile boolean refreshQueued = false;

    private void scheduleRefresh() {
        if (refreshQueued) {
            return;
        }
        refreshQueued = true;
        javafx.application.Platform.runLater(() -> {
            refreshQueued = false;
            refresh();
        });
    }

    // ------------------------------------------------------------------ transactions

    private Node buildTransactionsTab() {
        var month = YearMonth.now();
        monthIncome.setText(VND.format(ctx.finance().monthSummary(month).income()));
        HBox summary = new HBox(24,
                new VBox(2, new Label("Thu nhập tháng này"), monthIncome),
                new VBox(2, new Label("Chi tiêu tháng này"), monthExpense),
                new VBox(2, new Label("Ròng"), monthNet));
        summary.getStyleClass().add("card");

        txSearch.setPromptText("Tìm giao dịch (mô tả, tag)…");
        txSearch.textProperty().addListener((o, a, b) -> refreshTransactions());
        txTypeFilter.getItems().addAll("(tất cả)", "INCOME", "EXPENSE");
        txTypeFilter.setValue("(tất cả)");
        txTypeFilter.setOnAction(e -> refreshTransactions());
        Button addTx = new Button("+ Thêm giao dịch");
        addTx.getStyleClass().add("primary-btn");
        addTx.setOnAction(e -> openTransactionEditor(null));
        Button delTx = new Button("🗑 Xóa");
        delTx.setOnAction(e -> deleteSelectedTransaction());

        HBox toolbar = new HBox(8, txSearch, new Label("Loại:"), txTypeFilter, addTx, delTx);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(txSearch, Priority.ALWAYS);

        buildTxTable();
        txTable.setPlaceholder(new EmptyState("💰", "Chưa có giao dịch nào trong phạm vi dữ liệu."));

        return new VBox(10, summary, toolbar, txTable);
    }

    private void buildTxTable() {
        TableColumn<Transaction, String> dateCol = new TableColumn<>("Ngày");
        dateCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().date.toString()));

        TableColumn<Transaction, Transaction> typeCol = new TableColumn<>("Loại");
        typeCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue()));
        typeCol.setCellFactory(c -> new TableCell<>() {
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

        TableColumn<Transaction, String> amountCol = new TableColumn<>("Số tiền");
        amountCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(VND.format(p.getValue().amount) + " đ"));
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<Transaction, String> catCol = new TableColumn<>("Danh mục");
        catCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(safeCategory(p.getValue())));

        TableColumn<Transaction, String> methodCol = new TableColumn<>("Thanh toán");
        methodCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(methodLabel(p.getValue().method)));

        TableColumn<Transaction, String> descCol = new TableColumn<>("Mô tả");
        descCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().description));

        TableColumn<Transaction, String> tagsCol = new TableColumn<>("Tags");
        tagsCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(String.join(", ", p.getValue().tags)));

        txTable.getColumns().setAll(List.of(dateCol, typeCol, amountCol, catCol, methodCol, descCol, tagsCol));
        txTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        txTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && txTable.getSelectionModel().getSelectedItem() != null) {
                openTransactionEditor(txTable.getSelectionModel().getSelectedItem());
            }
        });
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

    private void refreshTransactions() {
        var q = txSearch.getText();
        var typeFilter = txTypeFilter.getValue();
        var list = ctx.finance().all().stream()
                .filter(t -> typeFilter == null || "(tất cả)".equals(typeFilter) || t.type.name().equals(typeFilter))
                .filter(t -> q == null || q.isBlank()
                        || t.description.toLowerCase().contains(q.toLowerCase())
                        || t.tags.stream().anyMatch(tag -> tag.toLowerCase().contains(q.toLowerCase())))
                .toList();
        txTable.getItems().setAll(list);
        var month = YearMonth.now();
        var summary = ctx.finance().monthSummary(month);
        monthIncome.setText(VND.format(summary.income()) + " đ");
        monthExpense.setText(VND.format(summary.expense()) + " đ");
        monthNet.setText(VND.format(summary.net()) + " đ");
        monthNet.getStyleClass().removeAll("metric-pos", "metric-neg");
        monthNet.getStyleClass().add(summary.net().signum() >= 0 ? "metric-pos" : "metric-neg");
        refreshQuickCategories();
    }

    private void refreshQuickCategories() {
        var selected = quickCategory.getValue();
        quickCategory.getItems().setAll(ctx.finance().categories(TxType.EXPENSE));
        quickCategory.getItems().addAll(ctx.finance().categories(TxType.INCOME));
        if (selected != null) {
            quickCategory.getItems().stream().filter(c -> c.id.equals(selected.id)).findFirst()
                    .ifPresent(quickCategory.getSelectionModel()::select);
        }
    }

    void openTransactionEditor(Transaction existing) {
        javafx.scene.control.Dialog<Transaction> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(existing == null ? "Thêm giao dịch" : "Sửa giao dịch");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);

        DatePicker date = new DatePicker(existing == null ? LocalDate.now() : existing.date);
        ComboBox<TxType> type = new ComboBox<>(FXCollections.observableArrayList(TxType.values()));
        type.setValue(existing == null ? TxType.EXPENSE : existing.type);
        TextField amount = new TextField(existing == null ? "" : existing.amount.toPlainString());
        amount.setPromptText("VD: 1250000");
        ComboBox<Category> category = new ComboBox<>();
        Runnable reloadCategories = () -> {
            category.getItems().setAll(ctx.finance().categories(type.getValue()));
            if (existing != null) {
                category.getItems().stream().filter(c -> c.id.equals(existing.categoryId)).findFirst()
                        .ifPresent(category.getSelectionModel()::select);
            } else {
                category.getSelectionModel().selectFirst();
            }
        };
        type.setOnAction(e -> reloadCategories.run());
        reloadCategories.run();
        ComboBox<PaymentMethod> method = new ComboBox<>(FXCollections.observableArrayList(PaymentMethod.values()));
        method.setValue(existing == null ? PaymentMethod.CASH : existing.method);
        TextField description = new TextField(existing == null ? "" : existing.description);
        TextField tags = new TextField(existing == null ? "" : String.join(", ", existing.tags));

        VBox content = new VBox(10,
                new HBox(10, labeled("Ngày", date), labeled("Loại", type), labeled("Phương thức", method)),
                new HBox(10, labeled("Số tiền (VND) *", amount), labeled("Danh mục", category)),
                labeled("Mô tả", description),
                labeled("Tags (phân cách bằng dấu phẩy)", tags));
        content.setPadding(new javafx.geometry.Insets(10));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            try {
                BigDecimal value = new BigDecimal(amount.getText().trim());
                Category cat = category.getValue();
                if (existing == null) {
                    var tagsSet = new java.util.LinkedHashSet<String>();
                    for (String t : tags.getText().split(",")) {
                        if (!t.isBlank()) {
                            tagsSet.add(t.trim());
                        }
                    }
                    return ctx.finance().addTransaction(date.getValue(), type.getValue(), value, cat.id,
                            method.getValue(), description.getText(), tagsSet, null, false);
                }
                existing.date = date.getValue();
                existing.type = type.getValue();
                existing.amount = value;
                existing.categoryId = cat.id;
                existing.method = method.getValue();
                existing.description = description.getText();
                existing.tags.clear();
                for (String t : tags.getText().split(",")) {
                    if (!t.isBlank()) {
                        existing.tags.add(t.trim());
                    }
                }
                ctx.finance().updateTransaction(existing);
                return existing;
            } catch (NumberFormatException ex) {
                Dialogs.error("Số tiền không hợp lệ", "Hãy nhập số tiền dạng số, ví dụ 1250000");
                return null;
            } catch (RuntimeException ex) {
                Dialogs.error("Không lưu được giao dịch", ex.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
        refresh();
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
        Button addBudget = new Button("+ Đặt ngân sách");
        addBudget.getStyleClass().add("primary-btn");
        addBudget.setOnAction(e -> openBudgetEditor(null));
        Button delBudget = new Button("🗑");
        delBudget.setOnAction(e -> {
            Budget b = budgetTable.getSelectionModel().getSelectedItem();
            if (b != null && Dialogs.confirm("Xóa ngân sách", "Xóa ngân sách tháng " + b.month + "?")) {
                ctx.finance().deleteBudget(b.id);
            }
        });
        HBox toolbar = new HBox(8, addBudget, delBudget);
        return new VBox(10, toolbar, budgetTable);
    }

    private void buildBudgetTable() {
        TableColumn<Budget, String> monthCol = new TableColumn<>("Tháng");
        monthCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(p.getValue().month.toString()));
        TableColumn<Budget, String> catCol = new TableColumn<>("Danh mục");
        catCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(safeBudgetCategory(p.getValue())));
        TableColumn<Budget, String> limitCol = new TableColumn<>("Hạn mức");
        limitCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(VND.format(p.getValue().limitAmount) + " đ"));
        TableColumn<Budget, String> spentCol = new TableColumn<>("Đã chi");
        spentCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(VND.format(
                ctx.finance().budgetStatus(p.getValue().month).stream()
                        .filter(s -> s.budget().id.equals(p.getValue().id))
                        .findFirst().map(FinanceService.BudgetStatus::spent).orElse(BigDecimal.ZERO)) + " đ"));
        TableColumn<Budget, String> remainCol = new TableColumn<>("Còn lại");
        remainCol.setCellValueFactory(p -> new ReadOnlyObjectWrapper<>(VND.format(
                ctx.finance().budgetStatus(p.getValue().month).stream()
                        .filter(s -> s.budget().id.equals(p.getValue().id))
                        .findFirst().map(FinanceService.BudgetStatus::remaining).orElse(BigDecimal.ZERO)) + " đ"));
        budgetTable.getColumns().setAll(List.of(monthCol, catCol, limitCol, spentCol, remainCol));
        budgetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private String safeBudgetCategory(Budget b) {
        try {
            return ctx.finance().categoryById(b.categoryId).name;
        } catch (RuntimeException e) {
            return "?";
        }
    }

    void openBudgetEditor(Budget existing) {
        javafx.scene.control.Dialog<Budget> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(existing == null ? "Đặt ngân sách" : "Sửa ngân sách");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        ComboBox<YearMonth> month = new ComboBox<>();
        for (int i = -2; i <= 3; i++) {
            month.getItems().add(YearMonth.now().plusMonths(i));
        }
        month.setValue(existing == null ? YearMonth.now() : existing.month);
        ComboBox<Category> category = new ComboBox<>(FXCollections.observableArrayList(
                ctx.finance().categories(TxType.EXPENSE)));
        if (existing != null) {
            category.getItems().stream().filter(c -> c.id.equals(existing.categoryId)).findFirst()
                    .ifPresent(category.getSelectionModel()::select);
        } else {
            category.getSelectionModel().selectFirst();
        }
        TextField limit = new TextField(existing == null ? "" : existing.limitAmount.toPlainString());
        VBox content = new VBox(10, new HBox(10, labeled("Tháng", month), labeled("Danh mục chi", category)),
                labeled("Hạn mức (VND)", limit));
        content.setPadding(new javafx.geometry.Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE || category.getValue() == null) {
                return null;
            }
            try {
                BigDecimal value = new BigDecimal(limit.getText().trim());
                return ctx.finance().addBudget(month.getValue(), category.getValue().id, value);
            } catch (NumberFormatException e) {
                Dialogs.error("Số tiền không hợp lệ", "Nhập số, ví dụ 5000000");
                return null;
            } catch (RuntimeException e) {
                Dialogs.error("Không lưu được ngân sách", e.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
        refresh();
    }

    // ------------------------------------------------------------------ debts

    private Node buildDebtsTab() {
        buildDebtTable();
        Button addDebt = new Button("+ Thêm khoản");
        addDebt.getStyleClass().add("primary-btn");
        addDebt.setOnAction(e -> openDebtEditor());
        Button settle = new Button("✓ Đã all-clear / tất toán");
        settle.setOnAction(e -> {
            Debt d = debtTable.getSelectionModel().getSelectedItem();
            if (d != null) {
                ctx.finance().settleDebt(d.id);
            }
        });
        Button delDebt = new Button("🗑");
        delDebt.setOnAction(e -> {
            Debt d = debtTable.getSelectionModel().getSelectedItem();
            if (d != null && Dialogs.confirm("Xóa khoản", "Xóa khoản \"" + d.party + "\"?")) {
                ctx.finance().deleteDebt(d.id);
            }
        });
        HBox toolbar = new HBox(8, addDebt, settle, delDebt);
        return new VBox(10, toolbar, debtTable);
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

    void openDebtEditor() {
        javafx.scene.control.Dialog<Debt> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Thêm khoản phải thu / phải trả");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);
        ComboBox<DebtDirection> dir = new ComboBox<>(FXCollections.observableArrayList(DebtDirection.values()));
        dir.setValue(DebtDirection.RECEIVABLE);
        TextField party = new TextField();
        TextField amount = new TextField();
        DatePicker due = new DatePicker(LocalDate.now().plusDays(7));
        TextArea note = new TextArea();
        note.setPrefRowCount(2);
        VBox content = new VBox(10, new HBox(10, labeled("Loại", dir), labeled("Đối tượng", party)),
                new HBox(10, labeled("Số tiền (VND)", amount), labeled("Hạn", due)),
                labeled("Ghi chú", note));
        content.setPadding(new javafx.geometry.Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            try {
                return ctx.finance().addDebt(dir.getValue(), party.getText(),
                        new BigDecimal(amount.getText().trim()), LocalDate.now(), due.getValue(), note.getText());
            } catch (NumberFormatException e) {
                Dialogs.error("Số tiền không hợp lệ", "Nhập số, ví dụ 2000000");
                return null;
            } catch (RuntimeException e) {
                Dialogs.error("Không lưu được", e.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
        refresh();
    }

    // ------------------------------------------------------------------ reports

    private Node buildReportsTab() {
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setLabel("VND");
        BarChart<String, Number> monthly = new BarChart<>(x, y);
        monthly.setTitle("Thu / chi 6 tháng gần nhất");
        monthly.setPrefHeight(300);

        var now = YearMonth.now();
        var incomeSeries = new javafx.scene.chart.XYChart.Series<String, Number>();
        incomeSeries.setName("Thu nhập");
        var expenseSeries = new javafx.scene.chart.XYChart.Series<String, Number>();
        expenseSeries.setName("Chi tiêu");
        for (int i = 5; i >= 0; i--) {
            YearMonth m = now.minusMonths(i);
            var s = ctx.finance().monthSummary(m);
            String label = m.format(java.time.format.DateTimeFormatter.ofPattern("MM/yyyy"));
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
                    .forEach(e -> {
                        String name = safeCategoryName(e.getKey());
                        categoryPie.getData().add(new PieChart.Data(name, e.getValue().doubleValue()));
                    });
        }

        HBox charts = new HBox(20, monthly, categoryPie);
        HBox.setHgrow(monthly, Priority.ALWAYS);
        return new VBox(10, charts);
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
            TextArea reportArea = new TextArea(String.join("\n",
                    report.messages) + "\n\n" + String.join("\n", report.errorLines));
            reportArea.setEditable(false);
            reportArea.setPrefRowCount(12);
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

    private void refresh() {
        refreshTransactions();
        budgetTable.getItems().setAll(ctx.finance().budgetsFor(YearMonth.now()));
        debtTable.getItems().setAll(ctx.finance().debts(null, null));
    }

    @Override
    public void openRecord(String recordId) {
        ctx.finance().all().stream().filter(t -> t.id.equals(recordId)).findFirst()
                .ifPresent(txTable.getSelectionModel()::select);
    }

    private VBox labeled(String labelText, Node field) {
        Label l = new Label(labelText);
        l.getStyleClass().add("dim-label");
        return new VBox(2, l, field);
    }
}
