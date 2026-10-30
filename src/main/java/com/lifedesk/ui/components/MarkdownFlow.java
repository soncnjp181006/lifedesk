package com.lifedesk.ui.components;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Markdown rendered with plain JavaFX nodes (TextFlow / VBox / GridPane) instead
 * of a WebView.
 *
 * <p>Why this exists: the chat used to spin up one {@code WebView} per assistant
 * reply and reload its HTML on every streaming tick. Every WebView is a full
 * WebKit page with its own native allocations, so a few replies pushed the JVM's
 * native heap past its limit and the process died with a malloc failure (the
 * "app tự thoát" crash). Text nodes are cheap, re-render in a millisecond and
 * keep the reply inside the JavaFX scene graph, which is also why streaming is
 * smooth now.
 *
 * <p>Supported: headings, bold/italic/strikethrough/highlight, inline code,
 * fenced code blocks, ordered/unordered/task lists, blockquotes, tables, rules
 * and links (click handled in Java). Colours and type come from CSS classes, so
 * every theme applies automatically.
 */
public class MarkdownFlow extends VBox {
    /** Called with an http(s) URL when a link is clicked. */
    private Consumer<String> linkHandler = url -> {
    };

    public MarkdownFlow() {
        getStyleClass().add("md-flow");
        setSpacing(7);
        setFillWidth(true);
        setMinWidth(0);
    }

    public void setLinkHandler(Consumer<String> handler) {
        this.linkHandler = handler == null ? url -> {
        } : handler;
    }

    /** Plain markdown (no streaming caret). */
    public void render(String markdown) {
        render(markdown, false);
    }

    /**
     * Render markdown.
     *
     * @param markdown markdown source; may be an incomplete prefix of a reply
     * @param caret    append a blinking-style caret, used while streaming
     */
    public void render(String markdown, boolean caret) {
        getChildren().clear();
        String source = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = List.of(source.split("\n", -1));
        int i = 0;
        StringBuilder paragraph = new StringBuilder();
        while (i < lines.size()) {
            String raw = lines.get(i);
            String line = raw.strip();
            if (line.isEmpty()) {
                flushParagraph(paragraph);
                i++;
                continue;
            }
            // ---- fenced code block
            if (line.startsWith("```")) {
                flushParagraph(paragraph);
                i = codeBlock(lines, i);
                continue;
            }
            // ---- table
            if (line.startsWith("|") && i + 1 < lines.size() && isTableSeparator(lines.get(i + 1))) {
                flushParagraph(paragraph);
                i = table(lines, i);
                continue;
            }
            // ---- heading
            int hashes = headingLevel(line);
            if (hashes > 0) {
                flushParagraph(paragraph);
                TextFlow heading = textFlow();
                inlineInto(heading, line.substring(hashes).strip());
                if (!heading.getChildren().isEmpty()) {
                    String cls = "md-h" + Math.min(hashes, 6);
                    for (Node n : heading.getChildren()) {
                        n.getStyleClass().add(cls);
                    }
                    if (hashes <= 2) {
                        VBox block = new VBox(heading);
                        block.getStyleClass().add("md-heading-block");
                        getChildren().add(block);
                    } else {
                        getChildren().add(heading);
                    }
                }
                i++;
                continue;
            }
            // ---- horizontal rule
            if (line.matches("-{3,}|\\*{3,}|_{3,}")) {
                flushParagraph(paragraph);
                Region rule = new Region();
                rule.getStyleClass().add("md-rule");
                getChildren().add(rule);
                i++;
                continue;
            }
            // ---- blockquote (consecutive lines)
            if (line.startsWith(">")) {
                flushParagraph(paragraph);
                List<String> quoted = new ArrayList<>();
                while (i < lines.size() && lines.get(i).strip().startsWith(">")) {
                    quoted.add(lines.get(i).strip().substring(1).strip());
                    i++;
                }
                VBox quote = new VBox(spacing(quoted.size()));
                quote.getStyleClass().add("md-quote");
                for (String q : quoted) {
                    if (q.isEmpty()) {
                        continue;
                    }
                    TextFlow flow = textFlow();
                    inlineInto(flow, q);
                    flow.getStyleClass().add("md-quote-text");
                    quote.getChildren().add(flow);
                }
                getChildren().add(quote);
                continue;
            }
            // ---- list item
            ListItem item = parseListItem(raw);
            if (item != null) {
                flushParagraph(paragraph);
                TextFlow flow = textFlow();
                Label marker = new Label(item.marker());
                marker.getStyleClass().add("md-list-marker");
                HBox row = new HBox(8, marker, flow);
                row.getStyleClass().add("md-list-row");
                row.setAlignment(Pos.TOP_LEFT);
                row.setPadding(new javafx.geometry.Insets(0, 0, 0, Math.min(item.indent(), 4) * 14));
                HBox.setHgrow(flow, Priority.ALWAYS);
                inlineInto(flow, item.text());
                getChildren().add(row);
                i++;
                continue;
            }
            // ---- paragraph
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(line);
            i++;
        }
        flushParagraph(paragraph);
        if (getChildren().isEmpty()) {
            Label placeholder = new Label("");
            placeholder.getStyleClass().add("md-empty");
            getChildren().add(placeholder);
        }
        if (caret) {
            addCaret();
        }
    }

    // ------------------------------------------------------------------ blocks

    /** Consume a ``` fence; an unterminated fence (streaming) still renders as code. */
    private int codeBlock(List<String> lines, int start) {
        String fence = lines.get(start).strip();
        String lang = fence.length() > 3 ? fence.substring(3).strip() : "";
        List<String> code = new ArrayList<>();
        int i = start + 1;
        while (i < lines.size() && !lines.get(i).strip().startsWith("```")) {
            code.add(lines.get(i));
            i++;
        }
        VBox box = new VBox(4);
        box.getStyleClass().add("md-code-box");
        if (!lang.isEmpty()) {
            Label langLabel = new Label(lang);
            langLabel.getStyleClass().add("md-code-lang");
            box.getChildren().add(langLabel);
        }
        Text text = new Text(String.join("\n", code));
        text.getStyleClass().add("md-code-block");
        TextFlow flow = new TextFlow(text);
        flow.getStyleClass().add("md-code-flow");
        box.getChildren().add(flow);
        getChildren().add(box);
        return i < lines.size() ? i + 1 : i;
    }

    private static boolean isTableSeparator(String line) {
        String s = line.strip();
        return s.startsWith("|") && s.replace("|", "").replace("-", "").replace(":", "")
                .replace(" ", "").isEmpty() && s.contains("-");
    }

    /** True for a row whose every cell is a ---/---: alignment marker. */
    private static boolean isSeparatorCells(List<String> cells) {
        if (cells.isEmpty()) {
            return false;
        }
        for (String cell : cells) {
            if (!cell.matches(":?-{2,}:?")) {
                return false;
            }
        }
        return true;
    }

    /** Markdown pipe table → GridPane with a header row. */
    private int table(List<String> lines, int start) {
        List<List<String>> rows = new ArrayList<>();
        int i = start;
        while (i < lines.size() && lines.get(i).strip().startsWith("|")) {
            rows.add(splitRow(lines.get(i)));
            i++;
        }
        if (rows.isEmpty()) {
            return i;
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(1);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        for (int c = 0; c < cols; c++) {
            javafx.scene.layout.ColumnConstraints cc = new javafx.scene.layout.ColumnConstraints();
            cc.setMinWidth(60);
            cc.setPercentWidth(100.0 / cols);
            grid.getColumnConstraints().add(cc);
        }
        int rowIndex = 0;
        for (int r = 0; r < rows.size(); r++) {
            // The |---|---| alignment row carries no content and takes no grid row.
            if (isSeparatorCells(rows.get(r))) {
                continue;
            }
            boolean header = rowIndex == 0;
            for (int c = 0; c < cols; c++) {
                String cell = c < rows.get(r).size() ? rows.get(r).get(c) : "";
                TextFlow flow = textFlow();
                inlineInto(flow, cell);
                VBox cellBox = new VBox(flow);
                cellBox.getStyleClass().add(header ? "md-th" : "md-td");
                cellBox.setMaxWidth(Double.MAX_VALUE);
                grid.add(cellBox, c, rowIndex);
            }
            rowIndex++;
        }
        VBox wrap = new VBox(grid);
        wrap.getStyleClass().add("md-table-wrap");
        getChildren().add(wrap);
        return i;
    }

    private static List<String> splitRow(String line) {
        String s = line.strip();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : s.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && n < 6 && line.charAt(n) == '#') {
            n++;
        }
        if (n == 0 || n >= line.length() || line.charAt(n) != ' ') {
            return 0;
        }
        return n;
    }

    private record ListItem(int indent, String marker, String text) {
    }

    private static ListItem parseListItem(String raw) {
        int indent = 0;
        int p = 0;
        while (p < raw.length() && (raw.charAt(p) == ' ' || raw.charAt(p) == '\t')) {
            indent += raw.charAt(p) == '\t' ? 2 : 1;
            p++;
        }
        String rest = raw.substring(p);
        if (rest.length() > 2 && (rest.startsWith("- ") || rest.startsWith("* ") || rest.startsWith("+ "))) {
            String body = rest.substring(2).strip();
            if (body.startsWith("[ ] ")) {
                return new ListItem(indent / 2, "☐", body.substring(4));
            }
            if (body.startsWith("[x] ") || body.startsWith("[X] ")) {
                return new ListItem(indent / 2, "☑", body.substring(4));
            }
            return new ListItem(indent / 2, "•", body);
        }
        // ordered: "1. text" / "1) text"
        int digits = 0;
        while (p + digits < raw.length() && Character.isDigit(raw.charAt(p + digits))) {
            digits++;
        }
        if (digits > 0 && p + digits + 1 < raw.length()) {
            String tail = raw.substring(p + digits);
            if (tail.startsWith(". ") || tail.startsWith(") ")) {
                return new ListItem(indent / 2, raw.substring(p, p + digits) + ".", tail.substring(2));
            }
        }
        return null;
    }

    private static double spacing(int lines) {
        return lines > 1 ? 5 : 0;
    }

    private void flushParagraph(StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        TextFlow flow = textFlow();
        inlineInto(flow, paragraph.toString());
        getChildren().add(flow);
        paragraph.setLength(0);
    }

    private TextFlow textFlow() {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("md-paragraph");
        flow.setMinWidth(0);
        flow.setMaxWidth(Double.MAX_VALUE);
        return flow;
    }

    private void addCaret() {
        Text caret = new Text("▍");
        caret.getStyleClass().add("md-caret");
        Node last = getChildren().isEmpty() ? null : getChildren().get(getChildren().size() - 1);
        if (last instanceof TextFlow flow) {
            flow.getChildren().add(caret);
        } else if (last instanceof VBox box && !box.getChildren().isEmpty()
                && box.getChildren().get(box.getChildren().size() - 1) instanceof TextFlow flow) {
            flow.getChildren().add(caret);
        } else if (last instanceof HBox row && row.getChildren().size() > 1
                && row.getChildren().get(1) instanceof TextFlow flow) {
            flow.getChildren().add(caret);
        } else {
            TextFlow flow = new TextFlow(caret);
            flow.getStyleClass().add("md-paragraph");
            getChildren().add(flow);
        }
    }

    // ------------------------------------------------------------------ inline

    private void inlineInto(TextFlow flow, String source) {
        String s = source == null ? "" : source;
        int i = 0;
        int plainStart = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    addPlain(flow, s, plainStart, i);
                    flow.getChildren().add(styled(s.substring(i + 1, end), "md-inline-code"));
                    i = end + 1;
                    plainStart = i;
                    continue;
                }
            } else if (c == '*' || c == '_') {
                boolean pair = i + 1 < s.length() && s.charAt(i + 1) == c;
                int step = pair ? 2 : 1;
                // A lone underscore inside a word (snake_case) is literal text.
                boolean wordUnderscore = c == '_' && i > 0 && Character.isLetterOrDigit(s.charAt(i - 1));
                int end = wordUnderscore ? -1 : s.indexOf(c + "".repeat(step), i + step);
                if (end > i + step) {
                    addPlain(flow, s, plainStart, i);
                    Text text = styled(s.substring(i + step, end), pair ? "md-strong" : "md-em");
                    flow.getChildren().add(text);
                    i = end + step;
                    plainStart = i;
                    continue;
                }
            } else if (c == '~' && i + 1 < s.length() && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    addPlain(flow, s, plainStart, i);
                    flow.getChildren().add(styled(s.substring(i + 2, end), "md-del"));
                    i = end + 2;
                    plainStart = i;
                    continue;
                }
            } else if (c == '=' && i + 1 < s.length() && s.charAt(i + 1) == '=') {
                int end = s.indexOf("==", i + 2);
                if (end > i + 2) {
                    addPlain(flow, s, plainStart, i);
                    flow.getChildren().add(styled(s.substring(i + 2, end), "md-highlight"));
                    i = end + 2;
                    plainStart = i;
                    continue;
                }
            } else if (c == '!' && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                int close = s.indexOf(']', i + 2);
                int open = close < 0 ? -1 : s.indexOf('(', close);
                int end = open < 0 ? -1 : s.indexOf(')', open);
                if (end > 0) {
                    addPlain(flow, s, plainStart, i);
                    String alt = s.substring(i + 2, close);
                    flow.getChildren().add(styled(alt.isBlank() ? "[ảnh]" : "[ảnh: " + alt + "]", "md-image"));
                    i = end + 1;
                    plainStart = i;
                    continue;
                }
            } else if (c == '[') {
                int wikiEnd = s.startsWith("[[", i) ? s.indexOf("]]", i + 2) : -1;
                if (wikiEnd > 0) {
                    addPlain(flow, s, plainStart, i);
                    flow.getChildren().add(styled(s.substring(i + 2, wikiEnd), "md-wiki"));
                    i = wikiEnd + 2;
                    plainStart = i;
                    continue;
                }
                int close = s.indexOf(']', i + 1);
                if (close > 0 && close + 1 < s.length() && s.charAt(close + 1) == '(') {
                    int end = s.indexOf(')', close + 2);
                    if (end > 0) {
                        addPlain(flow, s, plainStart, i);
                        String label = s.substring(i + 1, close);
                        String url = s.substring(close + 2, end).strip();
                        flow.getChildren().add(link(label, url));
                        i = end + 1;
                        plainStart = i;
                        continue;
                    }
                }
            } else if (c == '$') {
                int end = s.indexOf('$', i + 1);
                if (end > i + 1) {
                    addPlain(flow, s, plainStart, i);
                    flow.getChildren().add(styled(s.substring(i + 1, end), "md-math"));
                    i = end + 1;
                    plainStart = i;
                    continue;
                }
            } else if ((c == 'h' || c == 'H') && s.regionMatches(true, i, "http", 0, 4)) {
                boolean boundary = i == 0 || Character.isWhitespace(s.charAt(i - 1))
                        || s.charAt(i - 1) == '(' || s.charAt(i - 1) == '[';
                if (boundary) {
                    int end = i;
                    while (end < s.length() && !Character.isWhitespace(s.charAt(end))) {
                        end++;
                    }
                    String url = s.substring(i, end);
                    addPlain(flow, s, plainStart, i);
                    flow.getChildren().add(link(url, url));
                    i = end;
                    plainStart = i;
                    continue;
                }
            }
            i++;
        }
        addPlain(flow, s, plainStart, s.length());
    }

    private Text link(String label, String url) {
        Text text = styled(label, "md-link");
        if (url.startsWith("http://") || url.startsWith("https://")) {
            text.setCursor(Cursor.HAND);
            text.setOnMouseClicked(e -> linkHandler.accept(url));
        } else if (url.startsWith("#") && label.isBlank()) {
            text.setText(url);
        }
        return text;
    }

    private static Text styled(String content, String styleClass) {
        Text text = new Text(content);
        text.getStyleClass().add(styleClass);
        return text;
    }

    /** Add plain text, skipping empty runs so the flow stays tidy. */
    private static void addPlain(TextFlow flow, String source, int from, int to) {
        if (to <= from) {
            return;
        }
        String chunk = source.substring(from, to);
        if (chunk.isBlank() && !chunk.contains(" ")) {
            return;
        }
        flow.getChildren().add(styled(chunk, "md-text"));
    }
}
