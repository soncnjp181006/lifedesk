package com.lifedesk.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownServiceTest {

    private final MarkdownService md = new MarkdownService();

    @Test
    void rawHtmlIsEscaped_noScriptExecutionPossible() {
        String html = md.toHtml("# Xin chào\n\n<script>alert('xss')</script>\n\n<img src=x onerror=alert(1)>");
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;img"), "img tag must be escaped, not rendered");
    }

    @Test
    void javascriptUrlsSanitized() {
        String html = md.toHtml("[click](javascript:alert(1))");
        assertFalse(html.contains("javascript:"), "javascript: URLs must be sanitized");
    }

    @Test
    void tablesHeadingsListsCodeRender() {
        String html = md.toHtml("""
                # Tiêu đề
                ## Mục
                | a | b |
                |---|---|
                | 1 | 2 |
                - item 1
                - item 2
                ```java
                int x = 1;
                ```
                """);
        assertTrue(html.contains("<h1"));
        assertTrue(html.contains("<table"));
        assertTrue(html.contains("item 1"));
        assertTrue(html.contains("<code"));
    }

    @Test
    void taskListCheckboxesRender() {
        String html = md.toHtml("- [x] xong\n- [ ] chưa\n");
        assertTrue(html.contains("checkbox"));
        assertTrue(html.contains("checked") || html.contains("CHECKED"));
    }

    @Test
    void wikiLinksResolvedToCustomScheme() {
        String html = md.toHtml("Xem [[Ghi chú khác]] nhé", target -> "lifedesk://note/id-123");
        assertTrue(html.contains("lifedesk://note/id-123"));
        // unresolved links degrade to plain text (no broken hrefs)
        String html2 = md.toHtml("Xem [[Ghi chú khác]] nhé", target -> null);
        assertFalse(html2.contains("lifedesk://"));
    }

    @Test
    void plainTextExtractionForSummaries() {
        String text = md.toPlainText("# Tiêu đề\n\n**đậm** và `code`");
        assertTrue(text.contains("đậm") && text.contains("code"));
        assertFalse(text.contains("**"));
        assertFalse(text.contains("<"));
    }

    @Test
    void wrappedPageContainsThemeColorsAndNoScripts() {
        var palette = new com.lifedesk.theme.ThemeManager.PreviewPalette("#fff", "#000", "#666", "#00f", "#ccc", "#eee");
        String page = md.wrapPage(md.toHtml("hello <b>world</b>"), palette);
        assertTrue(page.contains("<!DOCTYPE html>"));
        assertTrue(page.contains("#fff"));
        assertFalse(page.toLowerCase().contains("<script"));
    }
}
