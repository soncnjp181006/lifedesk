package com.lifedesk.ui.editors;

import com.lifedesk.app.AppContext;
import com.lifedesk.common.Tags;
import com.lifedesk.finance.Budget;
import com.lifedesk.finance.Category;
import com.lifedesk.finance.Debt;
import com.lifedesk.finance.DebtDirection;
import com.lifedesk.finance.PaymentMethod;
import com.lifedesk.finance.Transaction;
import com.lifedesk.finance.TxType;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.components.Card;
import com.lifedesk.ui.components.FormGrid;
import com.lifedesk.ui.components.NoteLinkPicker;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Modal editors for the finance module (transaction, budget, debt) plus the
 * category manager. Extracted from FinanceView so the view only owns layout
 * and tables; each editor pre-fills from {@code existing} (null = create) and
 * runs {@code onSaved} after the dialog closes.
 */
public final class FinanceDialogs {
    private FinanceDialogs() {
    }

    public static void editTransaction(AppContext ctx, Transaction existing, Runnable onSaved) {
        Dialog<Transaction> dialog = Dialogs.newDialog();
        dialog.setTitle(existing == null ? "Thêm giao dịch" : "Sửa giao dịch");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(Dialogs.SAVE, Dialogs.CANCEL);

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
                new HBox(10, FormGrid.labeled("Ngày", date), FormGrid.labeled("Loại", type),
                        FormGrid.labeled("Phương thức", method)),
                new HBox(10, FormGrid.labeled("Số tiền (VND) *", amount), FormGrid.labeled("Danh mục", category)),
                FormGrid.labeled("Mô tả", description),
                FormGrid.labeled("Tags (phân cách bằng dấu phẩy)", tags));
        content.setPadding(new Insets(10));
        if (existing != null) {
            NoteLinkPicker noteLinks = new NoteLinkPicker(ctx, "finance", existing.id,
                    "Ghi chú liên quan (bật/tắt để gắn hoặc gỡ — lưu ngay):");
            content.getChildren().add(noteLinks);
        }
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            try {
                BigDecimal value = new BigDecimal(amount.getText().trim());
                Category cat = category.getValue();
                if (existing == null) {
                    return ctx.finance().addTransaction(date.getValue(), type.getValue(), value, cat.id,
                            method.getValue(), description.getText(), Tags.parse(tags.getText()), null, false);
                }
                existing.date = date.getValue();
                existing.type = type.getValue();
                existing.amount = value;
                existing.categoryId = cat.id;
                existing.method = method.getValue();
                existing.description = description.getText();
                Tags.set(existing.tags, tags.getText());
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
        onSaved.run();
    }

    public static void editBudget(AppContext ctx, Budget existing, Runnable onSaved) {
        Dialog<Budget> dialog = Dialogs.newDialog();
        dialog.setTitle(existing == null ? "Đặt ngân sách" : "Sửa ngân sách");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(Dialogs.SAVE, Dialogs.CANCEL);
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
        CheckBox recurring = new CheckBox("Lặp lại hàng tháng (áp dụng cho mọi tháng)");
        recurring.setSelected(existing != null && existing.recurring);
        VBox content = new VBox(10,
                new HBox(10, FormGrid.labeled("Tháng", month), FormGrid.labeled("Danh mục chi", category)),
                FormGrid.labeled("Hạn mức (VND)", limit), recurring);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE || category.getValue() == null) {
                return null;
            }
            try {
                BigDecimal value = new BigDecimal(limit.getText().trim());
                return ctx.finance().addBudget(month.getValue(), category.getValue().id, value, recurring.isSelected());
            } catch (NumberFormatException e) {
                Dialogs.error("Số tiền không hợp lệ", "Nhập số, ví dụ 5000000");
                return null;
            } catch (RuntimeException e) {
                Dialogs.error("Không lưu được ngân sách", e.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
        onSaved.run();
    }

    public static void editDebt(AppContext ctx, Debt existing, Runnable onSaved) {
        Dialog<Debt> dialog = Dialogs.newDialog();
        dialog.setTitle(existing == null ? "Thêm khoản phải thu / phải trả" : "Sửa khoản");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(Dialogs.SAVE, Dialogs.CANCEL);
        ComboBox<DebtDirection> dir = new ComboBox<>(FXCollections.observableArrayList(DebtDirection.values()));
        dir.setValue(existing == null ? DebtDirection.RECEIVABLE : existing.direction);
        TextField party = new TextField(existing == null ? "" : existing.party);
        TextField amount = new TextField(existing == null ? "" : existing.amount.toPlainString());
        DatePicker due = new DatePicker(existing == null || existing.dueDate == null
                ? LocalDate.now().plusDays(7) : existing.dueDate);
        TextArea note = new TextArea(existing == null ? "" : existing.note);
        note.setPrefRowCount(3);
        note.setWrapText(true);
        VBox content = new VBox(10,
                new HBox(10, FormGrid.labeled("Loại", dir), FormGrid.labeled("Đối tượng", party)),
                new HBox(10, FormGrid.labeled("Số tiền (VND)", amount), FormGrid.labeled("Hạn", due)),
                FormGrid.labeled("Ghi chú", note));
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE) {
                return null;
            }
            try {
                BigDecimal value = new BigDecimal(amount.getText().trim());
                if (existing == null) {
                    return ctx.finance().addDebt(dir.getValue(), party.getText(), value,
                            LocalDate.now(), due.getValue(), note.getText());
                }
                existing.direction = dir.getValue();
                existing.party = party.getText();
                existing.amount = value;
                existing.dueDate = due.getValue();
                existing.note = note.getText();
                ctx.finance().updateDebt(existing);
                return existing;
            } catch (NumberFormatException e) {
                Dialogs.error("Số tiền không hợp lệ", "Nhập số, ví dụ 2000000");
                return null;
            } catch (RuntimeException e) {
                Dialogs.error("Không lưu được", e.getMessage());
                return null;
            }
        });
        dialog.showAndWait();
        onSaved.run();
    }

    /** Manage categories: rename + delete (delete blocked while transactions use the category). */
    public static void manageCategories(AppContext ctx, Runnable onSaved) {
        Dialog<Void> dialog = Dialogs.newDialog();
        dialog.setTitle("Quản lý danh mục");
        dialog.setHeaderText("Danh mục thu / chi");
        dialog.getDialogPane().getButtonTypes().add(Dialogs.CLOSE);

        ListView<Category> list = new ListView<>();
        list.getItems().setAll(ctx.finance().categories(null));
        list.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.name + "  (" + (item.type == TxType.INCOME ? "thu" : "chi") + ")");
            }
        });
        list.setPrefHeight(280);
        list.setPlaceholder(new com.lifedesk.ui.components.EmptyState("list",
                "Chưa có danh mục nào."));

        Button rename = new Button("Đổi tên");
        rename.setOnAction(e -> renameSelected(list, ctx));
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                renameSelected(list, ctx);
            }
        });
        Button delete = new Button("Xóa danh mục");
        delete.getStyleClass().add("danger-btn");
        delete.setOnAction(e -> {
            Category c = list.getSelectionModel().getSelectedItem();
            if (c == null) {
                return;
            }
            if (Dialogs.confirm("Xóa danh mục", "Xóa danh mục \"" + c.name + "\"?")) {
                try {
                    ctx.finance().deleteCategory(c.id);
                    list.getItems().setAll(ctx.finance().categories(null));
                } catch (RuntimeException ex) {
                    Dialogs.warn("Không xóa được", ex.getMessage());
                }
            }
        });
        Label hint = Card.dim("Danh mục đang có giao dịch sẽ không xóa được — hãy đổi tên thay thế.");

        dialog.getDialogPane().setContent(new VBox(10, list, new HBox(8, rename, delete), hint));
        dialog.setResizable(true);
        dialog.showAndWait();
        onSaved.run();
    }

    /** One rename path for the button and the double-click, so they cannot drift. */
    private static void renameSelected(javafx.scene.control.ListView<Category> list,
            com.lifedesk.app.AppContext ctx) {
        Category c = list.getSelectionModel().getSelectedItem();
        if (c == null) {
            return;
        }
        String newName = Dialogs.prompt("Đổi tên danh mục",
                "Tên mới cho \"" + c.name + "\":", c.name).orElse(null);
        if (newName == null || newName.isBlank()) {
            return;
        }
        try {
            ctx.finance().renameCategory(c.id, newName);
            list.getItems().setAll(ctx.finance().categories(null));
        } catch (RuntimeException ex) {
            Dialogs.error("Không đổi được tên", ex.getMessage());
        }
    }
}
