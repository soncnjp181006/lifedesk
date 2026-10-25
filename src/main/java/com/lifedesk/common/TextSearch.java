package com.lifedesk.common;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Text helpers for case/diacritic-insensitive matching (Vietnamese friendly). */
public final class TextSearch {
    private TextSearch() {
    }

    /** Lowercase, strip diacritics and collapse whitespace. */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String lowered = input.toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.replace('đ', 'd').replaceAll("\\s+", " ").trim();
    }

    /** True when every whitespace-separated query term is contained in the normalized haystack. */
    public static boolean matches(String haystack, String query) {
        String nHay = normalize(haystack);
        for (String term : terms(query)) {
            if (!nHay.contains(term)) {
                return false;
            }
        }
        return true;
    }

    public static List<String> terms(String query) {
        List<String> out = new ArrayList<>();
        if (query != null) {
            for (String t : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
                String n = normalize(t);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
        }
        return out;
    }
}
