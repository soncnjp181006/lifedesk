package com.lifedesk.markdown;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure text transformations for LaTeX-style typing assistance in the notes
 * editor — the essence of obsidian-latex-suite / quick-latex, no JavaFX
 * types so the behavior is unit-testable and the snippet table is data.
 *
 * <p>Supported helpers:
 * <ul>
 *   <li><b>Shorthand expansion</b> — a user table of {@code trigger:::replacement}
 *       lines; {@code #cursor} marks where the caret lands after expansion.</li>
 *   <li><b>Auto-fraction</b> — typing {@code /} inside math turns the preceding
 *       operand ({@code x}, {@code 12}, {@code (a+b)}) into {@code \frac{operand}{}}.</li>
 *   <li><b>Math parity</b> — whether the caret sits inside {@code $...$} /
 *       {@code $$...$$} (unescaped dollar count is odd).</li>
 * </ul>
 */
public final class LatexEngine {
    private LatexEngine() {
    }

    /** One shorthand: typing {@code trigger} expands to {@code replacement}. */
    public record Snippet(String trigger, String replacement) {
    }

    /** A pending text replacement relative to the caret position. */
    public record Expansion(int from, String replacement, int caretInReplacement) {
    }

    /** Parse a {@code trigger:::replacement;} shorthand table (one per line, ';' optional at EOL). */
    public static List<Snippet> parseShorthands(String table) {
        List<Snippet> out = new ArrayList<>();
        if (table == null || table.isBlank()) {
            return out;
        }
        for (String rawLine : table.split("\n")) {
            String line = rawLine.trim();
            if (line.endsWith(";")) {
                line = line.substring(0, line.length() - 1);
            }
            int sep = line.indexOf(":::");
            if (sep <= 0) {
                continue;
            }
            String trigger = line.substring(0, sep).trim();
            String replacement = line.substring(sep + 3).trim();
            if (!trigger.isEmpty()) {
                out.add(new Snippet(trigger, replacement));
            }
        }
        // Longest triggers first so "ngay mai" wins over "mai".
        out.sort((a, b) -> b.trigger().length() - a.trigger().length());
        return out;
    }

    /**
     * Find a shorthand whose trigger ends exactly at {@code caret} in
     * {@code textBeforeCaret} and is not part of a longer word (a letter
     * directly before the trigger start cancels it, so "delta" never
     * expands "ta").
     */
    public static Expansion matchShorthand(String text, int caret, List<Snippet> snippets) {
        if (snippets.isEmpty() || caret <= 0) {
            return null;
        }
        for (Snippet s : snippets) {
            int start = caret - s.trigger().length();
            if (start < 0 || !text.regionMatches(start, s.trigger(), 0, s.trigger().length())) {
                continue;
            }
            if (start > 0 && isWordChar(text.charAt(start - 1))) {
                continue; // part of a longer word
            }
            String replacement = s.replacement();
            int cursor = replacement.indexOf("#cursor");
            String expanded;
            int caretIn;
            if (cursor >= 0) {
                expanded = replacement.substring(0, cursor) + replacement.substring(cursor + "#cursor".length());
                caretIn = cursor;
            } else {
                expanded = replacement;
                caretIn = replacement.length();
            }
            return new Expansion(start, expanded, caretIn);
        }
        return null;
    }

    /**
     * Auto-fraction: the text right before the caret ends with an operand
     * followed by {@code /} (the user just typed the slash). Returns an
     * expansion replacing {@code operand/} with {@code \frac{operand}{}}.
     * Operands: an identifier/number run, or a balanced {@code (...)} /
     * {@code {...}} / {@code [...]} group whose outer brackets are stripped.
     */
    public static Expansion autoFraction(String text, int caret) {
        if (caret < 1 || caret > text.length() || text.charAt(caret - 1) != '/') {
            return null;
        }
        int end = caret - 1; // index of the '/'
        if (end == 0) {
            return null; // no operand before '/'
        }
        char last = text.charAt(end - 1);
        String operand;
        int start;
        if (last == ')' || last == '}' || last == ']') {
            char opening = last == ')' ? '(' : last == '}' ? '{' : '[';
            int open = -1;
            int depth = 0;
            for (int i = end - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == last) {
                    depth++;
                } else if (c == opening) {
                    depth--;
                    if (depth == 0) {
                        open = i;
                        break;
                    }
                }
            }
            if (open < 0) {
                return null; // unbalanced — leave alone
            }
            operand = text.substring(open + 1, end - 1);
            start = open;
        } else {
            start = end;
            while (start > 0 && isWordChar(text.charAt(start - 1))) {
                start--;
            }
            operand = text.substring(start, end);
        }
        if (operand.isBlank()) {
            return null;
        }
        String replacement = "\\frac{" + operand + "}{}";
        return new Expansion(start, replacement, replacement.length() - 1);
    }

    /** True when the caret sits inside an unescaped {@code $...$}/{@code $$...$$} span. */
    public static boolean insideMath(String text, int caret) {
        int delimiters = 0;
        int n = Math.min(caret, text.length());
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '$' && (i == 0 || text.charAt(i - 1) != '\\')) {
                delimiters++;
                if (i + 1 < n && text.charAt(i + 1) == '$') {
                    i++; // a $$ pair is ONE display-math delimiter
                }
            }
        }
        return delimiters % 2 == 1;
    }

    /**
     * Position right after the math span closing the span that starts at or
     * before {@code caret} — used by Tab-to-exit. Returns -1 when not in math
     * or no closing delimiter exists.
     */
    public static int mathExitPosition(String text, int caret) {
        if (!insideMath(text, caret)) {
            return -1;
        }
        // Scan forward for the next unescaped $, checking for $$ (display).
        for (int i = caret; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '$') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '$' && (i + 2 >= text.length() || text.charAt(i + 2) != '$')) {
                    return i + 2;
                }
                if (i + 1 < text.length() && text.charAt(i + 1) == '$') {
                    // '$$$' — treat conservatively as single-dollar close
                    return i + 1;
                }
                return i + 1;
            }
        }
        return -1;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\\';
    }

    /** The default snippet pack (latex-suite cheat-sheet essentials, Vietnamese-friendly). */
    public static String defaultShorthandTable() {
        return String.join("\n",
                "sr:::^{2}",
                "sr^:::^{#cursor}",
                "sq:::\\sqrt{#cursor}",
                "//:::\\frac{#cursor}{}",
                "ii:::\\int",
                "sum:::\\sum_{}^{#cursor}",
                "prod:::\\prod_{}^{#cursor}",
                "rr:::\\rightarrow",
                "lr:::\\Leftrightarrow",
                "aa:::\\forall",
                "ee:::\\exists",
                "in:::\\in",
                "nin:::\\notin",
                "sub:::\\subset",
                "cup:::\\cup",
                "cap:::\\cap",
                "empty:::\\emptyset",
                "inf:::\\infty",
                "deg:::\\degree",
                "lim:::\\lim_{#cursor}",
                "Gab:::\\alpha",
                "Gb:::\\beta",
                "Gg:::\\gamma",
                "Gd:::\\delta",
                "Ge:::\\varepsilon",
                "Gz:::\\zeta",
                "Gt:::\\theta",
                "Gl:::\\lambda",
                "Gm:::\\mu",
                "Gp:::\\pi",
                "Gs:::\\sigma",
                "Go:::\\omega",
                "Gph:::\\phi",
                "Gps:::\\psi",
                "GG:::\\Gamma",
                "GD:::\\Delta",
                "GT:::\\Theta",
                "GL:::\\Lambda",
                "GP:::\\Pi",
                "GS:::\\Sigma",
                "GO:::\\Omega",
                "x1:::x_{1}",
                "x2:::x_{2}",
                "xn:::x_{n}",
                "xx:::x^{#cursor}",
                "diff:::\\mathrm{d}x",
                "part:::\\partial",
                "nab:::\\nabla",
                "vec:::\\vec{#cursor}",
                "hat:::\\hat{#cursor}",
                "bar:::\\bar{#cursor}",
                "dot:::\\dot{#cursor}",
                "td:::\\text{#cursor}",
                "bold:::\\mathbf{#cursor}",
                "cal:::\\mathcal{#cursor}");
    }
}
