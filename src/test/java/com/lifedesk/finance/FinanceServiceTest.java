package com.lifedesk.finance;

import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.StorageIssueLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class FinanceServiceTest {

    @TempDir
    Path dir;

    private FinanceService service() {
        return new FinanceService(new JsonStore(dir, new StorageIssueLog()));
    }

    @Test
    void rejectsNonPositiveAmounts() {
        FinanceService svc = service();
        String cat = svc.categories(TxType.EXPENSE).get(0).id;
        assertThrows(RuntimeException.class, () -> svc.addTransaction(LocalDate.now(), TxType.EXPENSE,
                BigDecimal.ZERO, cat, PaymentMethod.CASH, "", null, null, false));
        assertThrows(RuntimeException.class, () -> svc.addTransaction(LocalDate.now(), TxType.EXPENSE,
                new BigDecimal("-5"), cat, PaymentMethod.CASH, "", null, null, false));
    }

    @Test
    void rejectsCategoryTypeMismatch() {
        FinanceService svc = service();
        String expenseCat = svc.categories(TxType.EXPENSE).get(0).id;
        assertThrows(RuntimeException.class, () -> svc.addTransaction(LocalDate.now(), TxType.INCOME,
                new BigDecimal("100"), expenseCat, PaymentMethod.CASH, "", null, null, false));
    }

    @Test
    void bigDecimalSummaryIsExact() {
        FinanceService svc = service();
        String exp = svc.categories(TxType.EXPENSE).get(0).id;
        String inc = svc.categories(TxType.INCOME).get(0).id;
        svc.addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("1000000.05"), exp, PaymentMethod.CASH, "a", null, null, false);
        svc.addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("2345.67"), exp, PaymentMethod.CASH, "b", null, null, false);
        svc.addTransaction(LocalDate.now(), TxType.INCOME, new BigDecimal("5000000.00"), inc, PaymentMethod.CASH, "c", null, null, false);

        var summary = svc.monthSummary(YearMonth.now());
        assertEquals(new BigDecimal("1002345.72"), summary.expense());
        assertEquals(new BigDecimal("5000000.00"), summary.income());
        assertEquals(new BigDecimal("3997654.28"), summary.net());
    }

    @Test
    void duplicateTransactionRejectedButAllowedWhenForced() {
        FinanceService svc = service();
        String cat = svc.categories(TxType.EXPENSE).get(0).id;
        svc.addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("50000"), cat, PaymentMethod.CASH,
                "cà phê", null, "ext-9", false);
        assertThrows(RuntimeException.class, () -> svc.addTransaction(LocalDate.now(), TxType.EXPENSE,
                new BigDecimal("50000"), cat, PaymentMethod.CASH, "cà phê", null, "ext-9", false));
        // allowDuplicate bypass is explicit
        svc.addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("50000"), cat, PaymentMethod.CASH,
                "cà phê", null, "ext-9", true);
        assertEquals(2, svc.transactionCount());
    }

    @Test
    void csvExportImportRoundTripWithDedupe() {
        FinanceService svc = service();
        String exp = svc.categories(TxType.EXPENSE).get(0).id;
        svc.addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("123456"), exp,
                PaymentMethod.BANK_CARD, "mua sắm; có dấu phẩy", null, null, false);

        Path csv = dir.resolve("export.csv");
        svc.exportCsv(csv, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1));

        // Re-import into a FRESH service (simulating re-run on same data): every row is duplicate.
        FinanceService fresh = new FinanceService(new JsonStore(dir, new StorageIssueLog()));
        CsvImportReport report = fresh.importCsv(csv);
        assertEquals(0, report.created);
        assertTrue(report.skippedDuplicates >= 1);
        assertEquals(0, report.errors);
        // Quoted description with comma survived the round trip
        assertTrue(fresh.all().stream().anyMatch(t -> t.description.equals("mua sắm; có dấu phẩy")));
    }

    @Test
    void csvImportSkipsBadRowsAndReportsErrors() throws Exception {
        FinanceService svc = service();
        Path csv = dir.resolve("bad.csv");
        java.nio.file.Files.writeString(csv, """
                id,date,type,category,amount,method,description,externalId
                x,not-a-date,EXPENSE,Khác,100,CASH,bad date,e1
                y,%s,EXPENSE,Khác,200,CASH,good row,e2
                z,%s,EXPENSE,Khác,-1,CASH,negative amount,e3
                """.formatted(LocalDate.now(), LocalDate.now()));
        CsvImportReport report = svc.importCsv(csv);
        assertEquals(1, report.created);
        assertEquals(2, report.errors);
    }

    @Test
    void budgetStatusTracksSpending() {
        FinanceService svc = service();
        var cat = svc.addCategory("Ăn uống test", TxType.EXPENSE);
        svc.addBudget(YearMonth.now(), cat.id, new BigDecimal("1000000"));
        svc.addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("750000"), cat.id,
                PaymentMethod.CASH, "x", null, null, false);
        var statuses = svc.budgetStatus(YearMonth.now());
        assertEquals(1, statuses.size());
        assertEquals(new BigDecimal("750000"), statuses.get(0).spent());
        assertEquals(new BigDecimal("250000"), statuses.get(0).remaining());
        assertEquals(0.75, statuses.get(0).usedRatio(), 1e-9);
    }

    @Test
    void categoryRenameAndDeleteGuards() {
        FinanceService svc = service();
        var cat = svc.categories(TxType.EXPENSE).get(0);
        // rename works
        svc.renameCategory(cat.id, "Ăn uống mới");
        assertEquals("Ăn uống mới", svc.categoryById(cat.id).name);
        // duplicate rename rejected
        var other = svc.addCategory("Di chuyển test", TxType.EXPENSE);
        assertThrows(RuntimeException.class, () -> svc.renameCategory(other.id, "Ăn uống mới"));
        // delete blocked while transactions reference the category
        svc.addTransaction(LocalDate.now(), TxType.EXPENSE, new BigDecimal("1000"), cat.id,
                PaymentMethod.CASH, "x", null, null, false);
        assertThrows(RuntimeException.class, () -> svc.deleteCategory(cat.id));
        // delete of an unused category also removes its budgets and works
        svc.addBudget(YearMonth.now(), other.id, new BigDecimal("500000"));
        svc.deleteCategory(other.id);
        assertTrue(svc.categories(TxType.EXPENSE).stream().noneMatch(c -> c.id.equals(other.id)));
        assertTrue(svc.budgetsFor(YearMonth.now()).stream().noneMatch(b -> b.categoryId.equals(other.id)));
        // renaming missing category fails
        assertThrows(RuntimeException.class, () -> svc.renameCategory("nope", "x"));
    }

    @Test
    void debtUpdateEditsExisting() {
        FinanceService svc = service();
        Debt d = svc.addDebt(DebtDirection.RECEIVABLE, "Nam", new BigDecimal("2000000"),
                LocalDate.now(), LocalDate.now().plusDays(7), "");
        d.party = "Nam cập nhật";
        d.amount = new BigDecimal("2500000");
        d.note = "thêm lãi";
        svc.updateDebt(d);
        var updated = svc.debts(DebtDirection.RECEIVABLE, DebtStatus.OPEN).get(0);
        assertEquals("Nam cập nhật", updated.party);
        assertEquals(new BigDecimal("2500000"), updated.amount);
        assertThrows(RuntimeException.class, () -> {
            d.amount = new BigDecimal("-1");
            svc.updateDebt(d);
        });
    }

    @Test
    void debtLifecycle() {
        FinanceService svc = service();
        Debt d = svc.addDebt(DebtDirection.RECEIVABLE, "Nam", new BigDecimal("2000000"),
                LocalDate.now(), LocalDate.now().plusDays(7), "tiền ăn trọn gói");
        assertEquals(1, svc.debts(DebtDirection.RECEIVABLE, DebtStatus.OPEN).size());
        svc.settleDebt(d.id);
        assertTrue(svc.debts(DebtDirection.RECEIVABLE, DebtStatus.SETTLED).size() == 1);
    }
}
