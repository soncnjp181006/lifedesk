package com.lifedesk.common;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Comma-separated tag field parsing shared by every editor dialog
 * (a tag list typed as "work, urgent , home" → {work, urgent, home}).
 */
public final class Tags {
    private Tags() {
    }

    /** Parse a comma-separated tag list into a trimmed, de-duplicated set (input order kept). */
    public static LinkedHashSet<String> parse(String commaSeparated) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (commaSeparated == null) {
            return out;
        }
        for (String t : commaSeparated.split(",")) {
            if (!t.isBlank()) {
                out.add(t.trim());
            }
        }
        return out;
    }

    /** Replace {@code target}'s contents with the parsed tags, keeping the same collection instance. */
    public static void set(Collection<String> target, String commaSeparated) {
        target.clear();
        target.addAll(parse(commaSeparated));
    }
}
