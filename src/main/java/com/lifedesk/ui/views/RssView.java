package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.rss.RssService;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.Fx;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.IconView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * RSS dashboard (rss-dashboard study): feed sidebar with folders → article
 * list with unread markers → distraction-free reader. Save-to-note turns an
 * article into a permanent markdown note; OPML keeps subscriptions portable.
 */
public class RssView extends View {
    private final ListView<RssService.Feed> feedList = new ListView<>();
    private final ListView<RssService.Article> articleList = new ListView<>();
    private final com.lifedesk.markdown.MarkdownPreviewPane reader = new com.lifedesk.markdown.MarkdownPreviewPane();
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    public RssView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "RSS";
    }

    @Override
    protected Node build() {
        Label title = new Label("RSS Dashboard");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button("Làm mới tất cả", new IconView("recycle", 15));
        refresh.getStyleClass().add("primary-btn");
        refresh.setOnAction(e -> refreshAll());
        Button addFeed = new Button("Thêm feed", new IconView("plus", 15));
        addFeed.setOnAction(e -> addFeed());
        Button opmlExport = new Button("Xuất OPML");
        opmlExport.setOnAction(e -> exportOpml());
        Button opmlImport = new Button("Nhập OPML");
        opmlImport.setOnAction(e -> importOpml());
        HBox header = new HBox(10, title, spacer, opmlImport, opmlExport, addFeed, refresh);
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- left: feeds
        feedList.setPrefWidth(240);
        feedList.setMinWidth(180);
        feedList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(RssService.Feed item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.title + (item.folder.isEmpty() ? "" : "  (" + item.folder + ")"));
                }
            }
        });
        feedList.getItems().setAll(ctx.rss().feeds());
        feedList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> filterArticles());
        Button delFeed = new Button("", new IconView("trash", 15));
        delFeed.getStyleClass().add("icon-btn");
        delFeed.setTooltip(new Tooltip("Xóa feed đang chọn"));
        delFeed.setOnAction(e -> {
            RssService.Feed f = feedList.getSelectionModel().getSelectedItem();
            if (f != null && Dialogs.confirm("Xóa feed", "Bỏ theo dõi \"" + f.title + "\"?")) {
                ctx.rss().removeFeed(f.id);
                feedList.getItems().setAll(ctx.rss().feeds());
                refreshArticleList();
            }
        });
        VBox feedPane = new VBox(8, new HBox(8, new Label("Feeds"), new Region(), delFeed), feedList);
        ((HBox) feedPane.getChildren().get(0)).getChildren().stream()
                .filter(n -> n instanceof Region).findFirst()
                .ifPresent(n -> HBox.setHgrow(n, Priority.ALWAYS));
        VBox.setVgrow(feedList, Priority.ALWAYS);

        // ---- middle: articles
        articleList.setPrefWidth(360);
        articleList.setMinWidth(280);
        articleList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(RssService.Article item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                boolean read = ctx.rss().isRead(item.url());
                Label title = new Label((read ? "" : "● ") + item.title());
                title.getStyleClass().add(read ? "dim-label" : "section-label");
                title.setWrapText(true);
                Label meta = new Label(item.feedTitle() + " — " + DATE_FMT.format(item.published()));
                meta.getStyleClass().add("dim-label");
                setGraphic(new VBox(2, title, meta));
                setText(null);
            }
        });
        articleList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> openArticle(b));
        VBox articlePane = new VBox(8, new Label("Bài viết"), articleList);
        VBox.setVgrow(articleList, Priority.ALWAYS);

        // ---- right: reader
        reader.render("Chọn một bài viết để đọc tại đây.", ctx.themeManager().previewPalette(ctx.config().theme));
        HBox readerBar = new HBox(8, saveButton(), new Region());
        HBox.setHgrow(readerBar.getChildren().get(1), Priority.ALWAYS);
        VBox readerPane = new VBox(8, readerBar, reader);
        VBox.setVgrow(reader, Priority.ALWAYS);

        SplitPane split = new SplitPane(feedPane, articlePane, readerPane);
        split.setDividerPositions(0.18, 0.42);
        VBox.setVgrow(split, Priority.ALWAYS);

        ctx.rss().changes().addListener(this::scheduleRefresh);
        refreshArticleList();
        return new VBox(12, header, split);
    }

    private void addFeed() {
        String url = Dialogs.prompt("Thêm feed", "URL RSS/Atom (vd: https://blog.example/feed.xml):", "").orElse(null);
        if (url == null || url.isBlank()) {
            return;
        }
        String folder = Dialogs.prompt("Thư mục (tùy chọn)", "Nhóm feed này vào thư mục:", "").orElse("");
        try {
            ctx.rss().addFeed(url, folder);
            feedList.getItems().setAll(ctx.rss().feeds());
            main.status("Đã thêm feed — bấm \"Làm mới tất cả\" để tải bài viết.");
        } catch (RuntimeException e) {
            Dialogs.error("Không thêm được feed", e.getMessage());
        }
    }

    private void refreshAll() {
        main.status("Đang tải feeds…");
        Fx.async(ctx.executors().io(),
                () -> ctx.rss().refreshAll(),
                errors -> {
                    feedList.getItems().setAll(ctx.rss().feeds());
                    refreshArticleList();
                    int count = ctx.rss().articles().size();
                    main.status("Đã tải " + count + " bài viết"
                            + (errors.isEmpty() ? "" : " — " + errors.size() + " feed lỗi"));
                    if (!errors.isEmpty()) {
                        Dialogs.warn("Một số feed lỗi", String.join("\n",
                                errors.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList()));
                    }
                },
                err -> Dialogs.error("Làm mới thất bại", err.getMessage()));
    }

    private void refreshArticleList() {
        RssService.Feed selected = feedList.getSelectionModel().getSelectedItem();
        var articles = ctx.rss().articles().stream()
                .filter(a -> selected == null || a.feedId().equals(selected.id))
                .toList();
        articleList.getItems().setAll(articles);
        if (articles.isEmpty()) {
            articleList.setPlaceholder(new EmptyState("scroll-text",
                    "Chưa có bài viết. Thêm feed rồi bấm \"Làm mới tất cả\"."));
        }
    }

    private void filterArticles() {
        refreshArticleList();
    }

    private void openArticle(RssService.Article article) {
        if (article == null) {
            return;
        }
        ctx.rss().markRead(article.url(), true);
        String body = article.summary() == null || article.summary().isBlank()
                ? "(Bài này không có tóm tắt — mở liên kết gốc để đọc đầy đủ.)"
                : article.summary();
        String markdown = "# " + article.title() + "\n\n"
                + article.feedTitle() + " — " + DATE_FMT.format(article.published()) + "  ·  "
                + article.url() + "\n\n---\n\n" + body;
        reader.render(markdown, ctx.themeManager().previewPalette(ctx.config().theme),
                target -> null);
        refreshArticleList();
    }

    /** Save the selected article into Notes as a markdown note (rss→note conversion). */
    private Button saveButton() {
        Button b = new Button("Lưu thành ghi chú", new IconView("sticky-note", 15));
        b.setOnAction(e -> {
            RssService.Article a = articleList.getSelectionModel().getSelectedItem();
            if (a == null) {
                return;
            }
            ctx.notes().create(a.title(), "rss",
                    "# " + a.title() + "\n\nNguồn: " + a.url() + " (" + a.feedTitle() + ")\n\n"
                            + (a.summary() == null ? "" : a.summary()),
                    java.util.Set.of("rss"));
            ctx.rss().markSaved(a.url());
            main.status("Đã lưu bài viết vào Ghi chú (thư mục rss).");
        });
        return b;
    }

    private void exportOpml() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Xuất OPML");
        chooser.setInitialFileName("lifedesk-feeds.opml");
        var file = chooser.showSaveDialog(main.stage());
        if (file == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(file.toPath(), ctx.rss().exportOpml());
            Dialogs.info("Xuất OPML", "Đã ghi: " + file.toPath());
        } catch (Exception e) {
            Dialogs.error("Xuất thất bại", e.getMessage());
        }
    }

    private void importOpml() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Nhập OPML");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("OPML", "*.opml", "*.xml"));
        var file = chooser.showOpenDialog(main.stage());
        if (file == null) {
            return;
        }
        try {
            int added = ctx.rss().importOpml(java.nio.file.Files.readString(file.toPath()));
            feedList.getItems().setAll(ctx.rss().feeds());
            Dialogs.info("Nhập OPML", "Đã thêm " + added + " feed.");
        } catch (Exception e) {
            Dialogs.error("Nhập thất bại", e.getMessage());
        }
    }

    @Override
    protected void refresh() {
        feedList.getItems().setAll(ctx.rss().feeds());
        refreshArticleList();
    }
}
