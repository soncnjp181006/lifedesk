package com.lifedesk.tasks;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Natural-language quick-add for tasks — one line, Todoist-style:
 * {@code "Nộp báo cáo @mai 9h30 #work !cao +Công việc"}.
 *
 * <p>Grammar (whitespace-separated tokens; everything unrecognized is title):
 * <ul>
 *   <li><b>@date</b>: {@code @homnay}, {@code @mai}, {@code @qua}, {@code @tuansau},
 *       weekday {@code @thu2}..{@code @cn} (next occurrence), ISO {@code @2026-01-05},
 *       or {@code @31/12}</li>
 *   <li><b>time</b>: bare {@code 9h}, {@code 9h30}, {@code 9:30}</li>
 *   <li><b>#tag</b>: tag (repeatable)</li>
 *   <li><b>+group</b>: task group (single token)</li>
 *   <li><b>!priority</b>: {@code !khan}/{@code !khẩn}, {@code !cao}, {@code !thap}</li>
 *   <li><b>rest</b>: title (required — a line with only tokens is not a task)</li>
 * </ul>
 *
 * <p>Date tokens mirror {@code com.lifedesk.finance.QuickAddParser} so the two
 * quick-add bars behave the same way.
 */
public final class TaskQuickAdd {
    private TaskQuickAdd() {
    }

    /** A parsed quick-add line; priority falls back to MEDIUM when not given. */
    public record Parsed(String title, LocalDate dueDate, LocalTime dueTime,
                         Priority priority, String group, List<String> tags) {
    }

    public static Parsed parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        LocalDate date = null;
        LocalTime time = null;
        Priority priority = null;
        String group = null;
        List<String> tags = new ArrayList<>();
        StringBuilder title = new StringBuilder();
        for (String t : input.trim().split("\\s+")) {
            if (t.startsWith("@") && t.length() > 1) {
                LocalDate d = parseDateToken(t.substring(1));
                if (d != null) {
                    date = d;
                    continue;
                }
            }
            LocalTime parsedTime = parseTimeToken(t);
            if (parsedTime != null) {
                time = parsedTime;
                continue;
            }
            if (t.startsWith("#") && t.length() > 1) {
                tags.add(t.substring(1));
                continue;
            }
            if (t.startsWith("+") && t.length() > 1 && group == null) {
                group = t.substring(1);
                continue;
            }
            Priority p = parsePriorityToken(t);
            if (p != null) {
                priority = p;
                continue;
            }
            if (title.length() > 0) {
                title.append(' ');
            }
            title.append(t);
        }
        String trimmed = title.toString().trim();
        if (trimmed.isEmpty()) {
            return null; // tokens only — no title, no task
        }
        return new Parsed(trimmed, date, time,
                priority == null ? Priority.MEDIUM : priority,
                group == null ? "" : group, tags);
    }

    /** "9h" → 09:00, "9h30"/"9:30" → 09:30. Null when not a time token. */
    static LocalTime parseTimeToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.toLowerCase();
        if (t.matches("\\d{1,2}h(\\d{2})?")) {
            String[] parts = t.split("h");
            int hour = Integer.parseInt(parts[0]);
            int minute = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 0;
            return hour <= 23 && minute <= 59 ? LocalTime.of(hour, minute) : null;
        }
        if (t.matches("\\d{1,2}:\\d{2}")) {
            try {
                LocalTime time = LocalTime.parse(t);
                return time;
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return null;
    }

    static Priority parsePriorityToken(String token) {
        return switch (token.toLowerCase()) {
            case "!khan", "!khẩn", "!gap", "!gấp" -> Priority.URGENT;
            case "!cao", "!high" -> Priority.HIGH;
            case "!thap", "!thấp", "!low" -> Priority.LOW;
            default -> null;
        };
    }

    /**
     * Vietnamese keywords, weekday names (next occurrence — today rolls to next
     * week), ISO date, or dd/MM. Null when unrecognized.
     */
    static LocalDate parseDateToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.toLowerCase();
        switch (v) {
            case "homnay", "today", "nay":
                return LocalDate.now();
            case "mai", "ngaymai", "tomorrow":
                return LocalDate.now().plusDays(1);
            case "qua", "homqua":
                return LocalDate.now().minusDays(1);
            case "tuansau", "tuauntiep":
                return LocalDate.now().plusWeeks(1);
            default:
                DayOfWeek dow = weekday(v);
                if (dow != null) {
                    LocalDate today = LocalDate.now();
                    int diff = (dow.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
                    if (diff == 0) {
                        diff = 7;
                    }
                    return today.plusDays(diff);
                }
                try {
                    return LocalDate.parse(v); // ISO yyyy-MM-dd
                } catch (DateTimeParseException ignored) {
                    // fall through to dd/MM
                }
                try {
                    String[] parts = v.split("/");
                    if (parts.length == 2) {
                        return LocalDate.of(LocalDate.now().getYear(),
                                Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
                    }
                } catch (RuntimeException ignored) {
                    // not a date token
                }
                return null;
        }
    }

    private static DayOfWeek weekday(String v) {
        return switch (v) {
            case "thu2", "t2" -> DayOfWeek.MONDAY;
            case "thu3", "t3" -> DayOfWeek.TUESDAY;
            case "thu4", "t4" -> DayOfWeek.WEDNESDAY;
            case "thu5", "t5" -> DayOfWeek.THURSDAY;
            case "thu6", "t6" -> DayOfWeek.FRIDAY;
            case "thu7", "t7" -> DayOfWeek.SATURDAY;
            case "cn", "chunhat" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }
}
