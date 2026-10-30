package com.lifedesk.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests for the pure markdown editing transformations. */
class MarkdownTextTest {

    @Test
    void wrapWithSelectionKeepsSelectedTextSelected() {
        MarkdownText.Edit e = MarkdownText.wrap("hello world", 6, 11, "**", "**", "đậm");
        assertEquals("**world**", e.inserted());
        assertEquals("hello **world**", e.apply("hello world"));
        // selection covers the inner text between the markers
        assertEquals(8, e.selStart());
        assertEquals(13, e.selEnd());
    }

    @Test
    void wrapWithoutSelectionInsertsPlaceholderPreSelected() {
        MarkdownText.Edit e = MarkdownText.wrap("abc", 3, 3, "*", "*", "nghiêng");
        assertEquals("*nghiêng*", e.inserted());
        assertEquals("abc*nghiêng*", e.apply("abc"));
        assertEquals(4, e.selStart());
        assertEquals(11, e.selEnd());
    }

    @Test
    void prefixLinesPrefixesEverySelectedLine() {
        MarkdownText.Edit e = MarkdownText.prefixLines("one\ntwo\nthree", 0, 7, 0, "- ");
        assertEquals("- one\n- two", e.inserted());
        assertEquals("- one\n- two\nthree", e.apply("one\ntwo\nthree"));
        assertEquals(0, e.selStart());
        assertEquals(e.inserted().length(), e.selEnd());
    }

    @Test
    void prefixLinesWithoutSelectionUsesCaretLineOnly() {
        // caret on line 2 ("two"), no selection
        MarkdownText.Edit e = MarkdownText.prefixLines("one\ntwo\nthree", 5, 5, 5, "## ");
        assertEquals("## two", e.inserted());
        assertEquals("one\n## two\nthree", e.apply("one\ntwo\nthree"));
    }

    @Test
    void prefixLinesOnLastLineWithoutTrailingNewline() {
        MarkdownText.Edit e = MarkdownText.prefixLines("one\nlast", 4, 4, 4, "> ");
        assertEquals("> last", e.inserted());
        assertEquals("one\n> last", e.apply("one\nlast"));
    }

    @Test
    void prefixEmptySelectionOnEmptyText() {
        MarkdownText.Edit e = MarkdownText.prefixLines("", 0, 0, 0, "- ");
        assertEquals("- ", e.inserted());
        assertEquals("- ", e.apply(""));
    }
}
