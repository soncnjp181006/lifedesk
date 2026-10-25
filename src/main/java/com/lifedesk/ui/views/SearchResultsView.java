package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.search.SearchDoc;
import com.lifedesk.search.SearchGroup;
import com.lifedesk.search.SearchResult;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
        Label title = new Label("Kết quả tìm kiếm");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        query.setPromptText("Nhập từ khóa và Enter…");
        query.setOnAction(e -> search(query.getText()));
        HBox header = new HBox(10, title, spacer, query);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(query, Priority.ALWAYS);

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
        Label hint = new Label("Chỉ mục được dựng lại tự động từ dữ liệu gốc (offline).");
        hint.getStyleClass().add("dim-label");

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
                    Label snippet = new Label(snippet(item.doc().content()));
                    snippet.getStyleClass().add("dim-label");
                    snippet.setWrapText(true);
                    setGraphic(new VBox(2, title2, snippet));
                    setText(null);
                }
            }
        });
        results.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && results.getSelectionModel().getSelectedItem() != null) {
                SearchResult hit = results.getSelectionModel().getSelectedItem();
                main.openRecord(hit.doc().type(), hit.doc().refId());
            }
        });

        results.setPlaceholder(new EmptyState("🔍", "Kết quả sẽ hiển thị ở đây sau khi bạn tìm kiếm."));
        groupsBox.getChildren().add(results);
        VBox.setVgrow(results, Priority.ALWAYS);

        return new VBox(10, header, filters, hint, groupsBox);
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
        if (all.isEmpty() && !query.getText().isBlank()) {
            results.setPlaceholder(new EmptyState("🔍", "Không tìm thấy kết quả nào cho \"" + query.getText() + "\"."));
        }
    }

    @Override
    public void onShow() {
        // keep last results
    }
}
