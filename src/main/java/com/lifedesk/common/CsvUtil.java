package com.lifedesk.common;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Minimal RFC-4180 style CSV reader/writer (comma, quotes, embedded newlines). */
public final class CsvUtil {
    private CsvUtil() {
    }

    public static List<String[]> read(Path file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        String text = Files.readString(file, StandardCharsets.UTF_8);
        // Strip UTF-8 BOM written by our own CSV export (Excel compatibility).
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\r') {
                // skip, handled by \n
            } else if (ch == '\n') {
                finishRow(cell, row, rows);
            } else {
                cell.append(ch);
            }
        }
        finishRow(cell, row, rows);
        return rows;
    }

    private static void finishRow(StringBuilder cell, List<String> row, List<String[]> rows) {
        row.add(cell.toString());
        cell.setLength(0);
        boolean allEmpty = row.stream().allMatch(String::isEmpty);
        if (!allEmpty) {
            rows.add(row.toArray(new String[0]));
        }
        row.clear();
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needQuote = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return needQuote ? "\"" + escaped + "\"" : escaped;
    }

    public static void write(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            // UTF-8 BOM so Excel opens Vietnamese content correctly.
            bw.write('\ufeff');
            for (String line : lines) {
                bw.write(line);
                bw.write("\r\n");
            }
        }
    }
}
