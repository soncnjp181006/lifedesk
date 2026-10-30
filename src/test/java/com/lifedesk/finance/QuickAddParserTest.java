package com.lifedesk.finance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickAddParserTest {

    @Test
    void parsesPlainAmountAndDescription() {
        var p = QuickAddParser.parse("250000 grab sáng");
        assertEquals(new BigDecimal("250000"), p.amount());
        assertEquals(TxType.EXPENSE, p.type());
        assertEquals("grab sáng", p.description());
        assertEquals(LocalDate.now(), p.date());
    }

    @Test
    void parsesKiloShorthand() {
        assertEquals(0, new BigDecimal("35000").compareTo(QuickAddParser.parseAmountToken("35k")));
        assertEquals(0, new BigDecimal("1200000").compareTo(QuickAddParser.parseAmountToken("1.2tr")));
        assertEquals(0, new BigDecimal("1200000").compareTo(QuickAddParser.parseAmountToken("1,2m")));
    }

    @Test
    void parsesDateKeywords() {
        assertEquals(LocalDate.now(), QuickAddParser.parseDateToken("homnay"));
        assertEquals(LocalDate.now().plusDays(1), QuickAddParser.parseDateToken("mai"));
        assertEquals(LocalDate.now().minusDays(1), QuickAddParser.parseDateToken("homqua"));
        assertEquals(LocalDate.of(2026, 1, 5), QuickAddParser.parseDateToken("2026-01-05"));
        assertEquals(LocalDate.of(LocalDate.now().getYear(), 12, 31), QuickAddParser.parseDateToken("31/12"));
        assertNull(QuickAddParser.parseDateToken("abc"));
    }

    @Test
    void parsesFullLineWithDate() {
        var p = QuickAddParser.parse("35k cà phê @mai");
        assertEquals(new BigDecimal("35000"), p.amount());
        assertEquals(LocalDate.now().plusDays(1), p.date());
        assertEquals("cà phê", p.description());
    }

    @Test
    void incomeKeywordSwitchesType() {
        var p = QuickAddParser.parse("5tr lương thu");
        assertEquals(TxType.INCOME, p.type());
        assertEquals(new BigDecimal("5000000"), p.amount());
    }

    @Test
    void nonAmountFirstTokenReturnsNull() {
        assertNull(QuickAddParser.parse("cà phê 35k"));
        assertNull(QuickAddParser.parse(""));
        assertNull(QuickAddParser.parse(null));
    }

    // ---- tolerant CSV amount parsing

    @Test
    void tolerantAmountPlain() {
        assertEquals(new BigDecimal("1250000"), FinanceService.parseTolerantAmount("1250000"));
        assertEquals(new BigDecimal("1.5"), FinanceService.parseTolerantAmount("1.5"));
    }

    @Test
    void tolerantAmountCurrencySymbols() {
        assertEquals(new BigDecimal("1234567"), FinanceService.parseTolerantAmount("1.234.567 đ"));
        assertEquals(new BigDecimal("1234567.89"), FinanceService.parseTolerantAmount("1,234,567.89 USD"));
        assertEquals(new BigDecimal("1234567"), FinanceService.parseTolerantAmount("1.234.567"));
        assertEquals(new BigDecimal("1.5"), FinanceService.parseTolerantAmount("1,5"));
    }

    @Test
    void tolerantAmountNegativeAndInvalid() {
        assertEquals(new BigDecimal("-500"), FinanceService.parseTolerantAmount("-500"));
        assertNull(FinanceService.parseTolerantAmount("abc"));
        assertNull(FinanceService.parseTolerantAmount(""));
    }
}
