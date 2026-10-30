package com.lifedesk.markdown;

import javafx.scene.control.IndexRange;
import javafx.scene.control.TextArea;

/**
 * Applies {@link MarkdownText} transformations to a TextArea, preserving the
 * selection the way a text editor expects (selected span stays selected,
 * block prefixes extend the selection over the affected lines).
 */
public final class MarkdownEditor {
    private MarkdownEditor() {
    }

    /** Wrap the selection in markdown markers (bold, italic, code, link...). */
    public static void wrapSelection(TextArea editor, String prefix, String suffix, String placeholder) {
        IndexRange sel = editor.getSelection();
        MarkdownText.Edit e = MarkdownText.wrap(editor.getText(), sel.getStart(), sel.getEnd(),
                prefix, suffix, placeholder);
        editor.replaceText(e.from(), e.to(), e.inserted());
        editor.selectRange(e.selStart(), e.selEnd());
        editor.requestFocus();
    }

    /** Prefix the selected lines (or the caret's line) — headings, lists, quotes. */
    public static void prefixSelectedLines(TextArea editor, String prefix) {
        IndexRange sel = editor.getSelection();
        MarkdownText.Edit e = MarkdownText.prefixLines(editor.getText(), sel.getStart(), sel.getEnd(),
                editor.getCaretPosition(), prefix);
        editor.replaceText(e.from(), e.to(), e.inserted());
        editor.selectRange(e.selStart(), e.selEnd());
        editor.requestFocus();
    }

    /** Insert a snippet at the caret and leave the caret after it. */
    public static void insertBlock(TextArea editor, String block) {
        int pos = editor.getCaretPosition();
        editor.insertText(pos, block);
        editor.positionCaret(pos + block.length());
        editor.requestFocus();
    }
}
