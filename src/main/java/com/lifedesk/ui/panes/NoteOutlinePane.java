package com.lifedesk.ui.panes;

import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Note outline panel — the essence of obsidian-quiet-outline: a heading tree
 * that stays visually stable while you type (expansion state is keyed by the
 * heading path and restored on every rebuild), a search box that hides
 * non-matching headings, a level switch bar (H1..H6) and click-to-jump with
 * the character offset of the heading line.
 */
public class NoteOutlinePane extends VBox {
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    /** One heading: level 1..6, its text, and the offset of its line start. */
    public record Heading(int level, String text, int position) {
    }

    private final TextField search = new TextField();
    private final TreeView<Heading> tree = new TreeView<>();
    private final ToggleGroup levelGroup = new ToggleGroup();
    private final Map<String, Set<String>> expandedByNote = new HashMap<>();
    private String noteId = "";
    private int maxLevel = 6;

    /**
     * @param onJump receives the character offset of a clicked heading
     */
    public NoteOutlinePane(Consumer<Integer> onJump) {
        setSpacing(6);
        search.setPromptText("Lọc đề mục…");
        com.lifedesk.ui.Fx.debounced(search, this::rebuild);

        HBox levelBar = new HBox(2);
        String[] names = {"H1", "H2", "H3", "H4", "H5", "H6"};
        ToggleButton all = new ToggleButton("Tất cả");
        all.setToggleGroup(levelGroup);
        all.setSelected(true);
        all.setTooltip(new Tooltip("Hiện mọi cấp đề mục"));
        all.setOnAction(e -> {
            maxLevel = 6;
            rebuild();
        });
        levelBar.getChildren().add(all);
        for (int i = 0; i < names.length; i++) {
            final int cap = i + 1;
            ToggleButton b = new ToggleButton(names[i]);
            b.setToggleGroup(levelGroup);
            b.setTooltip(new Tooltip("Chỉ hiện đến cấp " + cap));
            b.setOnAction(e -> {
                maxLevel = cap;
                rebuild();
            });
            levelBar.getChildren().add(b);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        levelBar.getChildren().add(spacer);

        tree.setShowRoot(false);
        tree.getStyleClass().add("outline-tree");
        tree.setCellFactory(v -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(Heading item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.text());
                }
            }
        });
        tree.setOnMouseClicked(e -> {
            Heading h = tree.getSelectionModel().getSelectedItem() == null
                    ? null : tree.getSelectionModel().getSelectedItem().getValue();
            if (h != null) {
                onJump.accept(h.position());
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        getChildren().addAll(search, levelBar, tree);
    }

    /** Feed the current editor text (call on change and when the note switches). */
    public void update(String newNoteId, String text) {
        this.noteId = newNoteId;
        this.currentText = text == null ? "" : text;
        rebuild();
    }

    /** Parse headings fresh and rebuild the tree, restoring expansion state. */
    private void rebuild() {
        Set<String> expanded = tree.getRoot() != null
                ? collectExpanded(tree.getRoot()) : expandedByNote.get(noteId);

        List<Heading> headings = parseHeadings();
        boolean filterActive = !search.getText().isBlank();
        String q = search.getText().trim().toLowerCase();

        TreeItem<Heading> root = new TreeItem<>();
        root.setExpanded(true);
        if (filterActive) {
            // Search mode: flat list of matches, everything expanded.
            for (Heading h : headings) {
                if (h.text().toLowerCase().contains(q)) {
                    root.getChildren().add(new TreeItem<>(h));
                }
            }
        } else {
            java.util.ArrayDeque<TreeItem<Heading>> stack = new java.util.ArrayDeque<>();
            stack.push(root);
            for (Heading h : headings) {
                if (h.level() > maxLevel) {
                    continue;
                }
                while (stack.size() > h.level()) {
                    stack.pop();
                }
                TreeItem<Heading> item = new TreeItem<>(h);
                stack.peek().getChildren().add(item);
                stack.push(item);
            }
        }
        applyExpanded(root, expanded);
        if (filterActive) {
            expandAll(root);
        }
        tree.setRoot(root.getChildren().isEmpty() ? null : root);
    }

    /** Parse headings from text (package-visible for tests). */
    public static List<Heading> parse(String text) {
        List<Heading> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                out.add(new Heading(m.group(1).length(), m.group(2).trim(), offset));
            }
            offset += line.length() + 1;
        }
        return out;
    }

    private String currentText = "";

    private List<Heading> parseHeadings() {
        return parse(currentText);
    }

    private void applyExpanded(TreeItem<Heading> node, Set<String> state) {
        for (TreeItem<Heading> child : node.getChildren()) {
            if (state != null) {
                child.setExpanded(state.contains(expansionKey(child)));
            }
            applyExpanded(child, state);
        }
    }

    private void expandAll(TreeItem<Heading> node) {
        node.setExpanded(true);
        for (TreeItem<Heading> c : node.getChildren()) {
            expandAll(c);
        }
    }

    private String expansionKey(TreeItem<Heading> item) {
        StringBuilder sb = new StringBuilder();
        for (TreeItem<Heading> t = item; t != null && t.getValue() != null; t = t.getParent()) {
            sb.insert(0, t.getValue().text()).insert(0, "▸");
        }
        return sb.toString();
    }

    private Set<String> collectExpanded(TreeItem<Heading> node) {
        Set<String> out = new HashSet<>();
        collect(node, out);
        return out;
    }

    private void collect(TreeItem<Heading> node, Set<String> out) {
        for (TreeItem<Heading> c : node.getChildren()) {
            if (c.isExpanded()) {
                out.add(expansionKey(c));
            }
            collect(c, out);
        }
    }
}
