package com.lifedesk.markdown;

import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

/**
 * Offline LaTeX → PNG rendering via JLaTeXMath (pure Java, no JS/network).
 *
 * <p>Used by the markdown preview and the AI chat output: math spans
 * ({@code $...$} inline, {@code $$...$$} display) become embedded
 * {@code data:image/png;base64,...} images. Invalid LaTeX falls back to the
 * raw source text — a broken formula must never blank out part of a document.
 *
 * <p>{@link #mathToHtml} runs on <em>rendered</em> HTML (after commonmark),
 * protecting {@code <pre>}/{@code <code>} regions so dollar signs inside code
 * are never interpreted as math.
 */
public final class MathRenderer {
    /** Render scale for crisp display on HiDPI screens. */
    private static final int MATH_POINT_SIZE = 22;

    private static final Pattern DISPLAY_MATH = Pattern.compile("\\$\\$(.+?)\\$\\$", Pattern.DOTALL);
    private static final Pattern INLINE_MATH =
            Pattern.compile("(?<!\\\\)\\$(?!\\s|\\$)((?:[^$\\\\]|\\\\.)+?)(?<!\\s)\\$");
    private static final Pattern HTML_CODE_BLOCK = Pattern.compile("<pre>[\\s\\S]*?</pre>");
    private static final Pattern HTML_CODE_SPAN = Pattern.compile("<code>[^<]*</code>");

    private MathRenderer() {
    }

    /**
     * Render {@code latex} to a base64 PNG data URI, or null when the source
     * does not parse. {@code displayMode} selects display vs. text style.
     */
    public static String toPngDataUri(String latex, boolean displayMode) {
        byte[] png = toPng(latex, displayMode);
        if (png == null) {
            return null;
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }

    /** Render to raw PNG bytes, or null when the source does not parse. */
    public static byte[] toPng(String latex, boolean displayMode) {
        if (latex == null || latex.isBlank()) {
            return null;
        }
        try {
            TeXFormula formula = new TeXFormula(latex.trim());
            BufferedImage img = (BufferedImage) formula.createBufferedImage(
                    displayMode ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT,
                    MATH_POINT_SIZE, java.awt.Color.BLACK, null);
            if (img == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            // ParseException and IO problems both mean "not renderable":
            // the caller keeps the raw text instead.
            return null;
        }
    }

    /**
     * Replace math spans in rendered HTML with {@code <img>} tags.
     * {@code $$..$$} becomes a block-level image, {@code $..$} an inline one;
     * unrenderable formulas stay visible as {@code <code>} so they can be fixed.
     */
    public static String mathToHtml(String html) {
        if (html == null || !html.contains("$")) {
            return html;
        }
        List<String> protectedRegions = new ArrayList<>();
        String text = protect(html, HTML_CODE_BLOCK, protectedRegions);
        text = protect(text, HTML_CODE_SPAN, protectedRegions);

        text = substitute(text, DISPLAY_MATH, true);
        text = substitute(text, INLINE_MATH, false);

        for (int i = 0; i < protectedRegions.size(); i++) {
            text = text.replace("\u0000CODE" + i + "\u0000", protectedRegions.get(i));
        }
        return text;
    }

    private static String protect(String text, Pattern region, List<String> store) {
        Matcher m = region.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            store.add(m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement("\u0000CODE" + (store.size() - 1) + "\u0000"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String substitute(String text, Pattern pattern, boolean displayMode) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String latex = m.group(1);
            String uri = toPngDataUri(latex, displayMode);
            String replacement;
            if (uri != null) {
                String alt = escapeHtmlAttr(latex);
                replacement = "<img class=\"" + (displayMode ? "math-display" : "math-inline")
                        + "\" alt=\"" + alt + "\" src=\"" + uri + "\">";
            } else {
                // Unrenderable: keep the source visible so the user can fix it.
                replacement = "<code>" + escapeHtmlAttr(m.group(0)) + "</code>";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String escapeHtmlAttr(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
