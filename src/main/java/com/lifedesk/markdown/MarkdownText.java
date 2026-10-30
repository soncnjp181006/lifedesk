package com.lifedesk.markdown;

/**
 * Pure text transformations behind the markdown editor toolbar — no JavaFX
 * types, so the behavior is unit-testable and new toolbar actions are one
 * function each. {@link Edit} describes a span replacement plus the resulting
 * selection; callers apply it to their widget.
 */
public final class MarkdownText {
    private MarkdownText() {
    }

    /** Replace {@code original[from,to)} with {@code inserted}, selecting [selStart,selEnd). */
    public record Edit(int from, int to, String inserted, int selStart, int selEnd) {
        public String apply(String original) {
            return original.substring(0, from) + inserted + original.substring(to);
        }
    }

    /**
     * Wrap the selected span in prefix/suffix markers; when nothing is selected
     * the placeholder text is inserted pre-wrapped and selected.
     */
    public static Edit wrap(String text, int start, int end, String prefix, String suffix, String placeholder) {
        String selected = end > start ? text.substring(start, end) : placeholder;
        return new Edit(start, end, prefix + selected + suffix,
                start + prefix.length(), start + prefix.length() + selected.length());
    }

    /**
     * Prefix every line of the selection (or, with no selection, the line the
     * caret is on) with {@code prefix} — headings, lists, quotes, checklists.
     */
    public static Edit prefixLines(String text, int selStart, int selEnd, int caret, String prefix) {
        boolean hasSelection = selEnd > selStart;
        int from = hasSelection ? selStart : text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
        int to = hasSelection ? selEnd : text.indexOf('\n', caret);
        if (to < 0) {
            to = text.length();
        }
        String[] lines = text.substring(from, to).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(prefix).append(lines[i]);
        }
        return new Edit(from, to, sb.toString(), from, from + sb.length());
    }
}
