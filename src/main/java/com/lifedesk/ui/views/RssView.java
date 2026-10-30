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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * RSS dashboard: feed sidebar with folders → article list with unread markers →
 * distraction-free reader. Save-to-note turns an article into a permanent
 * markdown note; OPML keeps subscriptions portable.
 *
 * <p>Layout: three equal-baseline panels in an HBox. The SplitPane this used to
 * be drew separators that stopped short of the panel edges and let each column's
 * height drift, which is why the reader column looked misaligned.
 */
public class RssView extends View {
    private final ListView<RssService.Feed> feedList = new ListView<>();
    private final ListView<RssService.Article> articleList = new ListView<>();
    private final com.lifedesk.markdown.MarkdownPreviewPane reader =
            new com.lifedesk.markdown.MarkdownPreviewPane();
    /** Last markdown shown in the reader (placeholder until an article opens). */
    private String readerMarkdown = "Chọn một bài viết để đọc tại đây.";
    private final Label articleCount = new Label();
    private final Label feedCount = new Label();
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
        Button opmlExport = new Button("Xuất OPML", new IconView("download", 15));
        opmlExport.setOnAction(e -> exportOpml());
        Button opmlImport = new Button("Nhập OPML");
        opmlImport.setOnAction(e -> importOpml());
        HBox header = new HBox(10, title, spacer, opmlImport, opmlExport, addFeed, refresh);
        header.getStyleClass().add("view-header");
        header.setAlignment(Pos.CENTER_LEFT);

        // ---- left: feeds
        feedList.getStyleClass().add("note-list");
        feedList.setMinWidth(0);
        feedList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(RssService.Feed item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.title);
                name.getStyleClass().add("section-label");
                name.setWrapText(true);
                int unread = unreadFor(item);
                Label meta = new Label((item.folder.isEmpty() ? "Không thư mục" : item.folder)
                        + (unread > 0 ? "  ·  " + unread + " chưa đọc" : ""));
                meta.getStyleClass().add("chat-hint");
                setGraphic(new VBox(2, name, meta));
                setText(null);
            }
        });
        feedList.getItems().setAll(ctx.rss().feeds());
        feedList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> filterArticles());
        Button delFeed = new Button("", new IconView("trash", 14));
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
        feedCount.getStyleClass().add("chip");
        feedList.setPlaceholder(new EmptyState("rss",
                "Chưa theo dõi feed nào.\nBấm \"Thêm feed\" hoặc nhập OPML."));
        VBox feedPanel = panel("Feeds", feedCount, delFeed, feedList);
        feedPanel.setMinWidth(190);
        feedPanel.setPrefWidth(230);
        feedPanel.setMaxWidth(300);

        // ---- middle: articles
        articleList.getStyleClass().add("note-list");
        articleList.setMinWidth(0);
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
                Label head = new Label(item.title());
                head.getStyleClass().add(read ? "dim-label" : "section-label");
                head.setWrapText(true);
                Label meta = new Label((read ? "" : "● ") + item.feedTitle() + " — "
                        + DATE_FMT.format(item.published()));
                meta.getStyleClass().add("chat-hint");
                setGraphic(new VBox(3, head, meta));
                setText(null);
            }
        });
        articleList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> openArticle(b));
        articleCount.getStyleClass().add("chip");
        VBox articlePanel = panel("Bài viết", articleCount, null, articleList);
        articlePanel.setMinWidth(250);
        articlePanel.setPrefWidth(330);
        articlePanel.setMaxWidth(420);

        // ---- right: reader
        reader.setMinHeight(0);
        VBox.setVgrow(reader, Priority.ALWAYS);
        renderReader("Chọn một bài viết để đọc tại đây.");
        VBox readerPanel = panel("Đọc bài", null, saveButton(), reader);
        HBox.setHgrow(readerPanel, Priority.ALWAYS);

        HBox columns = new HBox(12, feedPanel, articlePanel, readerPanel);
        columns.setFillHeight(true);
        VBox.setVgrow(columns, Priority.ALWAYS);

        ctx.rss().changes().addListener(this::scheduleRefresh);
        refreshArticleList();
        return new VBox(12, header, columns);
    }

    /**
     * One dashboard column. Panels are built by the same helper so their header
     * row, padding and baseline are identical — mismatched columns were the
     * "UI lệch" complaint on this screen.
     *
     * The panel is a double enclosure: a tinted shell with generous inner padding
     * around a card core, so a column reads as one object instead of a bordered box.
     */
    private VBox panel(String title, Label badge, Node action, Node body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("panel-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, heading);
        if (badge != null) {
            head.getChildren().add(badge);
        }
        head.getChildren().add(spacer);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("panel-head");
        if (action != null) {
            head.getChildren().add(action);
        }
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox box = new VBox(8, head, body);
        box.getStyleClass().add("panel");
        box.setMaxHeight(Double.MAX_VALUE);
        return box;
    }

    private int unreadFor(RssService.Feed feed) {
        int unread = 0;
        for (RssService.Article a : ctx.rss().articles()) {
            if (a.feedId().equals(feed.id) && !ctx.rss().isRead(a.url())) {
                unread++;
            }
        }
        return unread;
    }

    private void addFeed() {
        String url = Dialogs.prompt("Thêm feed", "URL RSS/Atom (vd: https://blog.example/feed.xml):", "")
                .orElse(null);
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
                                errors.entrySet().stream()
                                        .map(e -> e.getKey() + ": " + e.getValue()).toList()));
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
        articleCount.setText(articles.size() + "");
        feedCount.setText(ctx.rss().feeds().size() + "");
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
        renderReader(markdown);
        refreshArticleList();
    }

    /** Reader render that remembers the markdown, so a theme switch can re-paint it. */
    private void renderReader(String markdown) {
        readerMarkdown = markdown;
        reader.render(markdown, ctx.themeManager().previewPalette(ctx.config().theme),
                target -> null);
    }

    @Override
    public void onThemeChanged() {
        renderReader(readerMarkdown);
    }

    /** Save the selected article into Notes as a markdown note (rss→note conversion). */
    private Button saveButton() {
        Button b = new Button("", new IconView("sticky-note", 14));
        b.getStyleClass().add("icon-btn");
        b.setTooltip(new Tooltip("Lưu bài đang đọc thành ghi chú"));
        b.setOnAction(e -> {
            RssService.Article a = articleList.getSelectionModel().getSelectedItem();
            if (a == null) {
                main.status("Chọn một bài viết trước khi lưu.");
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
