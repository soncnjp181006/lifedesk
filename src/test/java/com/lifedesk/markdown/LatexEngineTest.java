package com.lifedesk.markdown;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatexEngineTest {

    @Test
    void shorthandTableParsesAndSortsLongestFirst() {
        List<LatexEngine.Snippet> snippets = LatexEngine.parseShorthands(
                "al:::\\alpha;\nalpha:::\\alpha\nx:::y");
        assertEquals(3, snippets.size());
        assertEquals("alpha", snippets.get(0).trigger());
    }

    @Test
    void shorthandExpandsWithCursor() {
        List<LatexEngine.Snippet> snippets = LatexEngine.parseShorthands("sq:::\\sqrt{#cursor}");
        LatexEngine.Expansion e = LatexEngine.matchShorthand("abc sq", 6, snippets);
        assertEquals(4, e.from());
        assertEquals("\\sqrt{}", e.replacement());
        assertEquals(6, e.caretInReplacement());
    }

    @Test
    void shorthandDoesNotFireInsideWord() {
        List<LatexEngine.Snippet> snippets = LatexEngine.parseShorthands("ta:::\\tau");
        // "delta" ends with "ta" but a letter precedes the trigger start.
        assertNull(LatexEngine.matchShorthand("delta", 5, snippets));
        LatexEngine.Expansion e = LatexEngine.matchShorthand("delta ", 6, snippets);
        assertNull(e);
        // standalone "ta" fires
        assertTrue(LatexEngine.matchShorthand("ta", 2, snippets) != null);
    }

    @Test
    void autoFractionSimpleOperand() {
        LatexEngine.Expansion e = LatexEngine.autoFraction("x/", 2);
        assertEquals(0, e.from());
        assertEquals("\\frac{x}{}", e.replacement());
        assertEquals(e.replacement().length() - 1, e.caretInReplacement());
    }

    @Test
    void autoFractionBracketedOperandStripsOuterParens() {
        LatexEngine.Expansion e = LatexEngine.autoFraction("1+(a+b)/", 8);
        assertEquals(2, e.from());
        assertEquals("\\frac{a+b}{}", e.replacement());
    }

    @Test
    void autoFractionOnlyForSlashAtCaret() {
        assertNull(LatexEngine.autoFraction("x", 1));
        assertNull(LatexEngine.autoFraction("/", 1)); // no operand
        // unbalanced bracket falls back to the trailing operand (latex-suite behavior)
        LatexEngine.Expansion fallback = LatexEngine.autoFraction("(a+b/", 5);
        assertEquals("\\frac{b}{}", fallback.replacement());
    }

    @Test
    void insideMathParity() {
        assertTrue(LatexEngine.insideMath("$x^2", 4));
        assertFalse(LatexEngine.insideMath("$x^2$", 6));
        assertTrue(LatexEngine.insideMath("$$\n\\frac{a}{b}\n", 14));
        assertFalse(LatexEngine.insideMath("$$\na\n$$\nx", 9));
        // escaped dollar does not open math
        assertFalse(LatexEngine.insideMath("\\$5", 3));
    }

    @Test
    void mathExitFindsClosingDollar() {
        // position just past the closing '$' (insertion point)
        assertEquals(5, LatexEngine.mathExitPosition("$x^2$abc", 4));
        // display math closes past both dollars
        assertEquals(8, LatexEngine.mathExitPosition("$$\nab\n$$", 4));
        assertEquals(-1, LatexEngine.mathExitPosition("no math", 3));
    }

    @Test
    void defaultTableParses() {
        List<LatexEngine.Snippet> snippets = LatexEngine.parseShorthands(LatexEngine.defaultShorthandTable());
        assertTrue(snippets.size() >= 40);
        assertTrue(snippets.stream().anyMatch(s -> s.trigger().equals("sq")));
    }
}
