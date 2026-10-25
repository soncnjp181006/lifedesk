package com.lifedesk.notes;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses [[wiki-style]] links and #tags out of markdown content. */
public final class NoteLinkParser {
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]]+)]]");
    private static final Pattern TAG = Pattern.compile("(^|\\s)#([\\p{L}0-9_-]+)");

    private NoteLinkParser() {
    }

    public static Set<String> wikiLinks(String content) {
        Set<String> out = new LinkedHashSet<>();
        if (content == null) {
            return out;
        }
        Matcher m = WIKI_LINK.matcher(content);
        while (m.find()) {
            String target = m.group(1).trim();
            if (!target.isEmpty()) {
                out.add(target);
            }
        }
        return out;
    }

    public static Set<String> tags(String content) {
        Set<String> out = new LinkedHashSet<>();
        if (content == null) {
            return out;
        }
        Matcher m = TAG.matcher(content);
        while (m.find()) {
            out.add(m.group(2));
        }
        return out;
    }
}
