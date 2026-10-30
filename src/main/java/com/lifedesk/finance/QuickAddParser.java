package com.lifedesk.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Natural-language quick-add parser for finance capture — the fastest entry
 * path from the finance-tracker study: {@code "35k cà phê @mai"},
 * {@code "1.2tr thuê nhà @2026-01-05"}, {@code "250000 grab"}.
 *
 * <p>Grammar (whitespace-separated):
 * <ul>
 *   <li><b>amount</b> (required, first token): plain number, or shorthand
 *       {@code 35k} (×1.000), {@code 1.2tr}/{@code 1.2m}/{@code 1.2trieu} (×1.000.000)</li>
 *   <li><b>@date</b> (optional): {@code @homnay}, {@code @mai}, {@code @ qua}
 *       → use {@code @homqua}, ISO {@code @2026-01-05}, or {@code @31/12}</li>
 *   <li><b>rest</b>: description; the finance service resolves a category
 *       from it (name contains) or falls back to the type default</li>
 * </ul>
 */
public final class QuickAddParser {
    private QuickAddParser() {
    }

    /** A parsed quick-add line; category resolution happens in FinanceService. */
    public record Parsed(BigDecimal amount, TxType type, LocalDate date, String description) {
    }

    public static Parsed parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        BigDecimal amount = parseAmountToken(tokens[0]);
        if (amount == null || amount.signum() <= 0) {
            return null; // first token must be the amount
        }
        TxType type = TxType.EXPENSE; // captures are spending by default; INCOME via explicit keyword below
        LocalDate date = LocalDate.now();
        StringBuilder desc = new StringBuilder();
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.startsWith("@")) {
                LocalDate parsed = parseDateToken(t.substring(1));
                if (parsed != null) {
                    date = parsed;
                    continue;
                }
            }
            String lower = t.toLowerCase();
            if (lower.equals("thu") || lower.equals("thunhap") || lower.equals("income")) {
                type = TxType.INCOME;
                continue;
            }
            if (desc.length() > 0) {
                desc.append(' ');
            }
            desc.append(t);
        }
        return new Parsed(amount, type, date, desc.toString().trim());
    }

    /** "35k" → 35000, "1.2tr" → 1200000, "250000" → 250000. Null when not an amount. */
    static BigDecimal parseAmountToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.toLowerCase();
        long multiplier = 1;
        if (t.endsWith("k")) {
            multiplier = 1_000;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("tr") || t.endsWith("m")) {
            multiplier = 1_000_000;
            t = t.substring(0, t.length() - (t.endsWith("tr") ? 2 : 1));
        } else if (t.endsWith("trieu")) {
            multiplier = 1_000_000;
            t = t.substring(0, t.length() - 5);
        } else if (t.endsWith("nghin") || t.endsWith("ngan")) {
            multiplier = 1_000;
            t = t.substring(0, t.lastIndexOf(t.endsWith("nghin") ? "nghin" : "ngan"));
        }
        if (t.isEmpty() || !t.matches("\\d+([.,]\\d+)?")) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(t.replace(',', '.'));
            return value.multiply(BigDecimal.valueOf(multiplier));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @date token: Vietnamese keywords, ISO date, or dd/MM. Null when unrecognized. */
    static LocalDate parseDateToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.toLowerCase();
        return switch (v) {
            case "homnay", "today", "nay" -> LocalDate.now();
            case "mai", "ngaymai", "tomorrow" -> LocalDate.now().plusDays(1);
            case "homqua", "qua", "yesterday" -> LocalDate.now().minusDays(1);
            default -> {
                try {
                    yield LocalDate.parse(v); // ISO yyyy-MM-dd
                } catch (DateTimeParseException ignored) {
                    // fall through to dd/MM
                }
                try {
                    String[] parts = v.split("/");
                    if (parts.length == 2) {
                        yield LocalDate.of(LocalDate.now().getYear(), Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[0]));
                    }
                } catch (RuntimeException ignored) {
                    // not a date token
                }
                yield null;
            }
        };
    }
}
