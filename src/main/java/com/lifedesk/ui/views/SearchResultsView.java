package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.search.SearchDoc;
import com.lifedesk.search.SearchGroup;
import com.lifedesk.search.SearchResult;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.components.Card;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Global search results grouped by module type; Enter opens the record. */
public class SearchResultsView extends View {
    private final TextField query = new TextField();
    private final Set<String> typeFilter = new HashSet<>();
    private final VBox groupsBox = new VBox(10);
    private List<SearchGroup> lastGroups = List.of();

    public SearchResultsView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "SEARCH";
    }

    @Override
    protected Node build() {
        query.setPromptText("Nhập từ khóa và Enter…");
        query.setOnAction(e -> search(query.getText()));
        HBox header = toolbar(query);

        // type filter chips
        HBox filters = new HBox(8);
        for (String type : List.of(SearchDoc.TASK, SearchDoc.NOTE, SearchDoc.TRANSACTION, SearchDoc.HABIT,
                SearchDoc.GOAL, SearchDoc.COURSE, SearchDoc.FLASHCARD, SearchDoc.HEALTH)) {
            CheckBox cb = new CheckBox(SearchDoc.displayType(type));
            cb.selectedProperty().addListener((o, a, b) -> {
                if (b) {
                    typeFilter.add(type);
                } else {
                    typeFilter.remove(type);
                }
                render(lastGroups);
            });
            filters.getChildren().add(cb);
        }
        Label hint = Card.dim("Chỉ mục dựng lại tự động từ dữ liệu gốc (offline). "
                + "Enter để mở kết quả đang chọn, ↑ ↓ để chuyển.");

        ListView<SearchResult> results = new ListView<>();
        results.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label title2 = new Label("[" + SearchDoc.displayType(item.doc().type()) + "] "
                            + item.doc().title());
                    Label snippet = Card.dim(snippet(item.doc().content()));
                    snippet.setWrapText(true);
                    setGraphic(new VBox(2, title2, snippet));
                    setText(null);
                }
            }
        });
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                openSelected(results);
            }
        });
        // The keyboard path. Ctrl+K, type, Enter now lands here with the first hit
        // already chosen; Enter again opens it. Before this, reading a result list
        // you could not act on without the mouse made the shortcut half a feature.
        results.setOnKeyReleased(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                openSelected(results);
            } else if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                // Focus moved to the list when the search ran, so the shell's
                // "Esc clears the search box" rule would no longer reach it.
                // Esc here means "let me rewrite the query".
                main.focusSearch();
            }
        });

        results.setPlaceholder(new EmptyState("search", "Kết quả sẽ hiển thị ở đây sau khi bạn tìm kiếm."));
        groupsBox.getChildren().add(results);
        VBox.setVgrow(results, Priority.ALWAYS);

        return page(header, filters, hint, groupsBox);
    }

    private void openSelected(javafx.scene.control.ListView<SearchResult> results) {
        SearchResult hit = results.getSelectionModel().getSelectedItem();
        if (hit != null) {
            main.openRecord(hit.doc().type(), hit.doc().refId());
        }
    }

    private static String snippet(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }

    public void search(String q) {
        query.setText(q);
        List<SearchGroup> groups = ctx.search().query(q, typeFilter, 30);
        lastGroups = groups;
        render(groups);
    }

    private void render(List<SearchGroup> groups) {
        if (groupsBox.getChildren().isEmpty() || !(groupsBox.getChildren().get(0) instanceof ListView)) {
            return;
        }
        @SuppressWarnings("unchecked")
        ListView<SearchResult> results = (ListView<SearchResult>) groupsBox.getChildren().get(0);
        var all = groups.stream().flatMap(g -> g.results().stream()).toList();
        results.getItems().setAll(all);
        if (!all.isEmpty()) {
            // The top hit is the one the user most likely meant, and a selection is
            // what turns the second Enter into an action instead of a no-op.
            results.getSelectionModel().select(0);
            results.requestFocus();
        }
        if (all.isEmpty() && !query.getText().isBlank()) {
            results.setPlaceholder(new EmptyState("search", "Không tìm thấy kết quả nào cho \"" + query.getText() + "\"."));
        }
    }
}
