package com.lifedesk.markdown;

import com.lifedesk.theme.ThemeManager;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.text.TextContentRenderer;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown rendering pipeline.
 *
 * Security: raw embedded HTML is always escaped ({@code escapeHtml(true)}) and
 * URLs are sanitized ({@code sanitizeUrls(true)}), so markdown content can
 * never inject scripts, run shell commands or execute code. It renders to an
 * HTML string displayed inside a sandboxed JavaFX WebView; no JavaScript from
 * the document is trusted (none survives escaping).
 */
public class MarkdownService {
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]]+)]]");

    private final List<Extension> extensions = List.of(
            TablesExtension.create(),
            TaskListItemsExtension.create());
    private final Parser parser = Parser.builder().extensions(extensions).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .urlSanitizer(new SafeUrlSanitizer())
            .extensions(extensions)
            .build();
    private final TextContentRenderer textRenderer = TextContentRenderer.builder()
            .extensions(extensions)
            .build();

    /**
     * URL whitelist: http/https/mailto, in-app lifedesk:// links and
     * relative anchors are allowed; anything else (javascript:, vbscript:,
     * data:, file:, ...) is stripped so a markdown document can never make
     * the preview navigate anywhere unsafe.
     */
    private static final class SafeUrlSanitizer implements org.commonmark.renderer.html.UrlSanitizer {
        @Override
        public String sanitizeLinkUrl(String url) {
            return whitelist(url);
        }

        @Override
        public String sanitizeImageUrl(String url) {
            return whitelist(url);
        }

        private String whitelist(String url) {
            if (url == null) {
                return "";
            }
            String lower = url.trim().toLowerCase();
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")
                    || lower.startsWith("lifedesk://") || lower.startsWith("#")
                    || !lower.matches("^[a-z][a-z0-9+.-]*:.*$")) {
                return url;
            }
            return "";
        }
    }

    /** Render markdown to sanitized HTML. Wiki links [[Title]] become plain text. */
    public String toHtml(String markdown) {
        return toHtml(markdown, null);
    }

    /**
     * Render markdown to sanitized HTML.
     *
     * @param linkResolver maps a [[wiki link]] target to an href (e.g. lifedesk://note/<id>);
     *                     null keeps the target as plain text
     */
    public String toHtml(String markdown, UnaryOperator<String> linkResolver) {
        if (markdown == null) {
            return "";
        }
        String prepared = rewriteWikiLinks(markdown, linkResolver);
        Node document = parser.parse(prepared);
        // Math pass runs on rendered HTML: $/$$ spans become offline-rendered
        // images (code regions are protected inside MathRenderer).
        return MathRenderer.mathToHtml(renderer.render(document));
    }

    /** Plain text extraction (used for extractive summaries and search previews). */
    public String toPlainText(String markdown) {
        if (markdown == null) {
            return "";
        }
        String prepared = rewriteWikiLinks(markdown, null);
        return textRenderer.render(parser.parse(prepared));
    }

    private String rewriteWikiLinks(String markdown, UnaryOperator<String> linkResolver) {
        if (!markdown.contains("[[")) {
            return markdown;
        }
        Matcher m = WIKI_LINK.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String target = m.group(1).trim();
            String replacement;
            if (linkResolver != null) {
                String href = linkResolver.apply(target);
                replacement = href != null ? "[" + target + "](" + href + ")" : target;
            } else {
                replacement = target;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Wrap rendered HTML in a themed page for the preview WebView. No scripts are included. */
    public String wrapPage(String bodyHtml, ThemeManager.PreviewPalette palette) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="utf-8">
                <style>
                  body { background: %s; color: %s; font-family: "Segoe UI", sans-serif;
                         font-size: 14px; line-height: 1.55; margin: 16px 22px; }
                  h1,h2,h3,h4 { color: %s; border-bottom: 1px solid %s; padding-bottom: 4px; }
                  a { color: %s; }
                  code { background: %s; padding: 1px 5px; border-radius: 4px;
                         font-family: Consolas, monospace; font-size: 13px; }
                  pre { background: %s; padding: 10px 12px; border-radius: 8px; overflow-x: auto;
                        border: 1px solid %s; }
                  pre code { background: transparent; padding: 0; }
                  table { border-collapse: collapse; margin: 8px 0; }
                  th, td { border: 1px solid %s; padding: 5px 10px; }
                  th { background: %s; }
                  blockquote { border-left: 3px solid %s; margin-left: 0; padding-left: 12px; color: %s; }
                  input[type=checkbox] { margin-right: 6px; }
                  hr { border: 0; border-top: 1px solid %s; }
                  img.math-inline { vertical-align: middle; margin: 0 1px; }
                  img.math-display { display: block; margin: 8px auto; }
                  img { max-width: 100%%; }
                </style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(
                palette.background(), palette.text(), palette.accent(), palette.border(),
                palette.accent(), palette.codeBg(), palette.codeBg(), palette.border(),
                palette.border(), palette.codeBg(), palette.accent(), palette.textDim(),
                palette.border(), bodyHtml);
    }
}
