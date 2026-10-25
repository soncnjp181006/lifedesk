package com.lifedesk.ui.components;

import com.lifedesk.app.AppContext;
import com.lifedesk.notes.NoteMeta;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Reusable picker that attaches/detaches a note to a record of another
 * module (finance transaction, habit, goal, health day...). Links live on
 * NoteMeta.links and are persisted immediately on toggle.
 */
public class NoteLinkPicker extends VBox {
    private final AppContext ctx;
    private final String module;
    private String recordId;
    private final ComboBox<NoteMeta> pick = new ComboBox<>();
    private final Label countLabel = new Label();

    public NoteLinkPicker(AppContext ctx, String module, String recordId, String caption) {
        this.ctx = ctx;
        this.module = module;
        this.recordId = recordId;
        setSpacing(3);

        pick.setItems(javafx.collections.FXCollections.observableArrayList(ctx.notes().list()));
        pick.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(NoteMeta item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title);
            }
        });
        pick.setButtonCell(pick.getCellFactory().call(null));
        pick.setPrefWidth(280);
        pick.setPromptText("Chọn ghi chú…");

        Button toggle = new Button("Bật/tắt liên kết");
        toggle.setOnAction(e -> {
            NoteMeta note = pick.getValue();
            if (note == null || recordId == null || recordId.isBlank()) {
                return;
            }
            ctx.notes().toggleLink(note.id, module, recordId);
            refreshCount();
        });

        HBox row = new HBox(8, pick, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        Label cap = new Label(caption);
        cap.getStyleClass().add("dim-label");
        getChildren().addAll(cap, row, countLabel);
        refreshCount();
    }

    /** Change the target record (e.g. health day date changed). */
    public void setRecordId(String newRecordId) {
        this.recordId = newRecordId;
        refreshCount();
    }

    public void refreshCount() {
        if (recordId == null || recordId.isBlank()) {
            countLabel.setText("Chưa có bản ghi để gắn ghi chú.");
            return;
        }
        var linked = ctx.notes().notesLinkedTo(module, recordId);
        countLabel.setText(linked.isEmpty() ? "Chưa gắn ghi chú nào."
                : "Đã gắn " + linked.size() + " ghi chú: "
                + linked.stream().map(n -> n.title).limit(3).reduce((a, b) -> a + ", " + b).orElse("")
                + (linked.size() > 3 ? "…" : ""));
    }
}
