package com.lifedesk.ui.views;

import com.lifedesk.app.AppContext;
import com.lifedesk.library.LibraryService;
import com.lifedesk.ui.Dialogs;
import com.lifedesk.ui.MainWindow;
import com.lifedesk.ui.View;
import com.lifedesk.ui.components.Card;
import com.lifedesk.ui.components.EmptyState;
import com.lifedesk.ui.components.FormGrid;
import com.lifedesk.ui.components.IconView;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Personal library (library-plugin study): cover-gallery navigation grouped
 * into shelves — "Đang dùng" (up next), "Kế hoạch", "Hoàn thành" — with
 * one-click progress ticks, ratings, and a stats header.
 */
public class LibraryView extends View {
    private final FlowPane shelves = new FlowPane(14, 14);
    private final Label statsLabel = new Label();
    private final ComboBox<String> kindFilter = new ComboBox<>();

    public LibraryView(AppContext ctx, MainWindow main) {
        super(ctx, main);
    }

    @Override
    public String id() {
        return "LIBRARY";
    }

    @Override
    protected Node build() {
        Label title = new Label("Thư viện cá nhân");
        title.getStyleClass().add("title-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statsLabel.getStyleClass().add("dim-label");
        kindFilter.getItems().addAll("Tất cả", "Book", "Movie", "Series", "Game", "Music", "Course");
        kindFilter.setValue("Tất cả");
        kindFilter.setOnAction(e -> rebuild());
        Button add = new Button("Thêm mục", new IconView("plus", 15));
        add.getStyleClass().add("primary-btn");
        add.setOnAction(e -> openEditor(null));
        HBox header = new HBox(10, title, spacer, statsLabel, new Label("Loại:"), kindFilter, add);
        header.getStyleClass().add("view-header");
        header.setAlignment(Pos.CENTER_LEFT);

        shelves.getStyleClass().add("library-shelves");
        ScrollPane scroll = new ScrollPane(shelves);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent;");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        ctx.library().changes().addListener(this::scheduleRefresh);
        rebuild();
        return new VBox(12, header, scroll);
    }

    private void rebuild() {
        shelves.getChildren().clear();
        String kind = kindFilter.getValue();
        List<LibraryService.Item> items = ctx.library().all().stream()
                .filter(i -> "Tất cả".equals(kind) || i.kind.name().equalsIgnoreCase(kind))
                .toList();
        var stats = ctx.library().stats();
        statsLabel.setText(stats.total() + " mục — đang dùng " + stats.inProgress()
                + " — hoàn thành " + stats.done() + " — đánh giá TB " + Math.round(stats.avgRating() * 10) / 10.0 + "/10");

        shelf("Đang dùng", items.stream().filter(i -> i.status == LibraryService.Status.IN_PROGRESS).toList());
        shelf("Kế hoạch", items.stream().filter(i -> i.status == LibraryService.Status.PLANNED).toList());
        shelf("Hoàn thành", items.stream().filter(i -> i.status == LibraryService.Status.DONE).toList());
        shelf("Tạm bỏ", items.stream().filter(i -> i.status == LibraryService.Status.DROPPED).toList());
        if (items.isEmpty()) {
            shelves.getChildren().add(new EmptyState("library-big",
                    "Thư viện trống. Thêm sách/phim/game/course đầu tiên của bạn."));
        }
    }

    private void shelf(String title, List<LibraryService.Item> items) {
        if (items.isEmpty()) {
            return;
        }
        Label head = new Label(title + "  (" + items.size() + ")");
        head.getStyleClass().add("section-label");
        FlowPane cards = new FlowPane(12, 12);
        for (LibraryService.Item item : items) {
            cards.getChildren().add(itemCard(item));
        }
        VBox box = new VBox(8, head, cards);
        box.setMaxWidth(Double.MAX_VALUE);
        shelves.getChildren().add(box);
    }

    /** One cover card: image (async), title, rating chips, progress tick + edit. */
    private Node itemCard(LibraryService.Item item) {
        Card card = new Card("");
        card.setPrefWidth(190);

        Node cover;
        if (item.coverUrl != null && !item.coverUrl.isBlank()) {
            ProgressIndicator loading = new ProgressIndicator(ProgressIndicator.INDETERMINATE_PROGRESS);
            loading.setPrefSize(40, 40);
            ImageView view = new ImageView();
            view.setFitWidth(160);
            view.setFitHeight(200);
            view.setPreserveRatio(true);
            view.setImage(new Image(item.coverUrl, 160, 200, true, true, true)); // background load
            cover = view;
        } else {
            Label placeholder = new Label(iconFor(item.kind));
            placeholder.setStyle("-fx-font-size: 40px;");
            placeholder.setAlignment(Pos.CENTER);
            placeholder.setPrefSize(160, 120);
            placeholder.getStyleClass().add("card");
            cover = placeholder;
        }

        Label titleLabel = new Label(item.title + (item.year > 0 ? " (" + item.year + ")" : ""));
        titleLabel.getStyleClass().add("form-label");
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(165);
        Label meta = new Label(kindLabel(item.kind)
                + (item.creator.isBlank() ? "" : " — " + item.creator)
                + (item.rating > 0 ? "  ★ " + item.rating + "/10" : "")
                + (item.progressTotal > 0 ? "  [" + item.progressCurrent + "/" + item.progressTotal + "]" : ""));
        meta.getStyleClass().add("dim-label");
        meta.setWrapText(true);

        Button tick = new Button("", new IconView("check", 14));
        tick.getStyleClass().add("icon-btn");
        tick.setTooltip(new Tooltip("+1 tiến độ (tự hoàn thành khi đủ)"));
        tick.setOnAction(e -> ctx.library().tickProgress(item.id));
        Button edit = new Button("", new IconView("pencil", 14));
        edit.getStyleClass().add("icon-btn");
        edit.setTooltip(new Tooltip("Sửa"));
        edit.setOnAction(e -> openEditor(item));
        Button del = new Button("", new IconView("trash", 14));
        del.getStyleClass().add("icon-btn");
        del.setTooltip(new Tooltip("Xóa"));
        del.setOnAction(e -> {
            if (Dialogs.confirm("Xóa mục", "Bỏ \"" + item.title + "\" khỏi thư viện?")) {
                ctx.library().delete(item.id);
            }
        });
        HBox actions = new HBox(4, tick, edit, del);

        card.content(cover, titleLabel, meta, actions);
        return card;
    }

    private String iconFor(LibraryService.Kind kind) {
        return switch (kind) {
            case BOOK -> "📕";
            case MOVIE -> "🎬";
            case SERIES -> "📺";
            case GAME -> "🎮";
            case MUSIC -> "🎵";
            case COURSE -> "🎓";
        };
    }

    private String kindLabel(LibraryService.Kind kind) {
        return switch (kind) {
            case BOOK -> "Sách";
            case MOVIE -> "Phim";
            case SERIES -> "Series";
            case GAME -> "Game";
            case MUSIC -> "Nhạc";
            case COURSE -> "Khóa học";
        };
    }

    private void openEditor(LibraryService.Item existing) {
        javafx.scene.control.Dialog<LibraryService.Item> dialog = com.lifedesk.ui.Dialogs.newDialog();
        dialog.setTitle(existing == null ? "Thêm vào thư viện" : "Sửa mục thư viện");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);

        TextField title = new TextField(existing == null ? "" : existing.title);
        ComboBox<LibraryService.Kind> kind = new ComboBox<>();
        kind.getItems().addAll(LibraryService.Kind.values());
        kind.setValue(existing == null ? LibraryService.Kind.BOOK : existing.kind);
        ComboBox<LibraryService.Status> status = new ComboBox<>();
        status.getItems().addAll(LibraryService.Status.values());
        status.setValue(existing == null ? LibraryService.Status.PLANNED : existing.status);
        TextField creator = new TextField(existing == null ? "" : existing.creator);
        Spinner<Integer> year = new Spinner<>(0, 2100, existing == null ? java.time.YearMonth.now().getYear() : existing.year);
        Spinner<Integer> rating = new Spinner<>(0, 10, existing == null ? 0 : existing.rating);
        TextField cover = new TextField(existing == null ? "" : existing.coverUrl);
        cover.setPromptText("URL ảnh bìa (tùy chọn)");
        Spinner<Integer> progressCurrent = new Spinner<>(0, 100000, existing == null ? 0 : existing.progressCurrent);
        Spinner<Integer> progressTotal = new Spinner<>(0, 100000, existing == null ? 0 : existing.progressTotal);
        javafx.scene.control.TextArea note = new javafx.scene.control.TextArea(existing == null ? "" : existing.note);
        note.setPrefRowCount(2);

        VBox content = new VBox(10,
                FormGrid.labeled("Tiêu đề *", title),
                new HBox(10, FormGrid.labeled("Loại", kind), FormGrid.labeled("Trạng thái", status)),
                new HBox(10, FormGrid.labeled("Tác giả/nhà sản xuất", creator),
                        FormGrid.labeled("Năm", year), FormGrid.labeled("Đánh giá (0-10)", rating)),
                FormGrid.labeled("Ảnh bìa (URL)", cover),
                new HBox(10, FormGrid.labeled("Tiến độ", progressCurrent), FormGrid.labeled("Tổng", progressTotal)),
                FormGrid.labeled("Ghi chú", note));
        content.setPadding(new javafx.geometry.Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(bt -> {
            if (bt.getButtonData() != javafx.scene.control.ButtonBar.ButtonData.OK_DONE || title.getText().isBlank()) {
                return null;
            }
            LibraryService.Item item = existing == null ? new LibraryService.Item() : existing;
            item.title = title.getText().trim();
            item.kind = kind.getValue();
            item.status = status.getValue();
            item.creator = creator.getText();
            item.year = year.getValue();
            item.rating = rating.getValue();
            item.coverUrl = cover.getText();
            item.progressCurrent = progressCurrent.getValue();
            item.progressTotal = progressTotal.getValue();
            item.note = note.getText();
            return item;
        });
        dialog.showAndWait().ifPresent(item -> {
            try {
                if (existing == null) {
                    // add() returns the persisted record; copy the dialog-only
                    // fields onto it instead of updating the unparsed instance.
                    LibraryService.Item created = ctx.library().add(item.title, item.kind,
                            item.creator, item.year, item.coverUrl);
                    created.status = item.status;
                    created.rating = item.rating;
                    created.progressCurrent = item.progressCurrent;
                    created.progressTotal = item.progressTotal;
                    created.note = item.note;
                    ctx.library().update(created);
                } else {
                    ctx.library().update(item);
                }
            } catch (RuntimeException e) {
                Dialogs.error("Không lưu được", e.getMessage());
            }
        });
    }

    @Override
    protected void refresh() {
        rebuild();
    }
}
