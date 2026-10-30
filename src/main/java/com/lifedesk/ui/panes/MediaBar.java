package com.lifedesk.ui.panes;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Pinned media player for media notes (obsidian-media-notes essence):
 * the player stays fixed above the scrolling note. Local audio/video (and
 * direct http media streams) play embedded with a seek bar, resume position
 * and a timestamp button that inserts {@code [HH:MM:SS]()} at the caret.
 * Anything unplayable (YouTube etc.) degrades to an "open external" button —
 * never a dead black box.
 */
public class MediaBar extends VBox {
    /** Resume positions per note id, kept for the session (media-notes "resume where you left off"). */
    private static final Map<String, Double> RESUME = new HashMap<>();

    private final Button playBtn = new Button("▶");
    private final Button timestampBtn = new Button("⏱ Chèn timestamp (Ctrl+I)");
    private final Button externalBtn = new Button("Mở ngoài ↗");
    private final Button removeBtn = new Button("Gỡ media");
    private final Slider seek = new Slider(0, 1, 0);
    private final Label timeLabel = new Label("00:00 / 00:00");
    private final Label urlLabel = new Label();
    private final MediaView videoView = new MediaView();

    private MediaPlayer player;
    private String noteId = "";
    private String currentUrl = "";
    private boolean externalOnly;
    private boolean seeking;
    private boolean resumed;

    /** @param onInsertTimestamp receives the "[HH:MM:SS]()" text for the editor caret */
    public MediaBar(java.util.function.Consumer<String> onInsertTimestamp, Runnable onRemove) {
        setSpacing(6);
        getStyleClass().add("card");

        playBtn.getStyleClass().add("primary-btn");
        playBtn.setTooltip(new Tooltip("Phát / tạm dừng"));
        playBtn.setOnAction(e -> togglePlay());
        timestampBtn.getStyleClass().add("ghost-btn");
        timestampBtn.setOnAction(e -> insertTimestamp());
        externalBtn.getStyleClass().add("ghost-btn");
        externalBtn.setTooltip(new Tooltip("Mở media bằng trình duyệt/hệ thống"));
        externalBtn.setOnAction(e -> openExternal());
        externalBtn.setVisible(false);
        externalBtn.setManaged(false);
        removeBtn.getStyleClass().add("icon-btn");
        removeBtn.setTooltip(new Tooltip("Gỡ liên kết media khỏi ghi chú"));
        removeBtn.setOnAction(e -> onRemove.run());

        seek.setPrefWidth(320);
        seek.setDisable(true);
        seek.valueProperty().addListener((o, was, now) -> {
            if (seeking && player != null) {
                player.seek(Duration.seconds(now.doubleValue()));
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8, playBtn, seek, timeLabel, timestampBtn, externalBtn, removeBtn, spacer);
        controls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(timestampBtn, Priority.NEVER);

        urlLabel.getStyleClass().add("dim-label");
        videoView.setFitWidth(320);
        videoView.setFitHeight(180);
        videoView.setPreserveRatio(true);
        videoView.setVisible(false);
        videoView.setManaged(false);

        getChildren().addAll(videoView, new HBox(8, urlLabel), controls);
        setMaxWidth(Double.MAX_VALUE);
    }

    /** Attach the player to a note's media link; unplayable links go external-only. */
    public void load(String newNoteId, String url) {
        unload();
        this.noteId = newNoteId;
        this.currentUrl = url == null ? "" : url.trim();
        urlLabel.setText("🎞 " + currentUrl);
        externalOnly = !isPlayable(currentUrl);
        boolean external = externalOnly;
        externalBtn.setVisible(external);
        externalBtn.setManaged(external);
        if (external) {
            playBtn.setDisable(true);
            seek.setDisable(true);
            timestampBtn.setDisable(true);
            timeLabel.setText("—");
            return;
        }
        playBtn.setDisable(false);
        timestampBtn.setDisable(false);
        try {
            Media media = new Media(mediaUri(currentUrl));
            player = new MediaPlayer(media);
            videoView.setMediaPlayer(player);
            player.setOnReady(() -> {
                double total = player.getTotalDuration().toSeconds();
                if (total > 0) {
                    seek.setMax(total);
                    seek.setDisable(false);
                }
                timeLabel.setText("00:00 / " + fmt(total));
                // Video vs audio: reveal the view only when there are frames.
                boolean video = media.getWidth() > 0;
                videoView.setVisible(video);
                videoView.setManaged(video);
                Double resume = RESUME.get(noteId);
                if (resume != null && resume > 1 && resume < total) {
                    player.seek(Duration.seconds(resume));
                }
                resumed = true;
                player.play();
                playBtn.setText("⏸");
            });
            player.currentTimeProperty().addListener((o, was, now) -> {
                if (!seek.isValueChanging() && !seeking) {
                    double t = now.toSeconds();
                    seek.setValue(t);
                    timeLabel.setText(fmt(t) + " / " + fmt(seek.getMax()));
                }
            });
            seek.valueChangingProperty().addListener((o, was, changing) -> seeking = changing);
            player.setOnError(() -> {
                // Unparseable media: degrade to external instead of a dead player.
                unloadPlayer();
                externalOnly = true;
                externalBtn.setVisible(true);
                externalBtn.setManaged(true);
                playBtn.setDisable(true);
                timestampBtn.setDisable(true);
                seek.setDisable(true);
                timeLabel.setText("không phát được — mở ngoài");
            });
            player.setOnEndOfMedia(() -> playBtn.setText("▶"));
        } catch (RuntimeException e) {
            unloadPlayer();
            externalOnly = true;
            externalBtn.setVisible(true);
            externalBtn.setManaged(true);
            playBtn.setDisable(true);
            timestampBtn.setDisable(true);
        }
    }

    /** Stop playback and remember the resume position. */
    public void unload() {
        if (player != null && currentUrl != null && !currentUrl.isBlank()) {
            RESUME.put(noteId, player.getCurrentTime().toSeconds());
        }
        unloadPlayer();
        externalOnly = false;
        resumed = false;
        videoView.setVisible(false);
        videoView.setManaged(false);
    }

    public boolean hasPlayer() {
        return player != null;
    }

    private java.util.function.Consumer<String> onTimestamp = s -> {
    };

    /** Wire the timestamp callback: text like "[00:01:23]()" goes to the editor caret. */
    public void setOnTimestamp(java.util.function.Consumer<String> callback) {
        this.onTimestamp = callback == null ? s -> {
        } : callback;
    }

    public void insertTimestamp() {
        if (externalOnly) {
            return;
        }
        String ts = player != null ? fmt(player.getCurrentTime().toSeconds()) : "00:00:00";
        onTimestamp.accept("[" + ts + "]()");
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        switch (player.getStatus()) {
            case PLAYING -> {
                player.pause();
                playBtn.setText("▶");
            }
            default -> {
                player.play();
                playBtn.setText("⏸");
            }
        }
    }

    private void openExternal() {
        try {
            String target = currentUrl.startsWith("http") ? currentUrl
                    : Path.of(currentUrl).toUri().toString();
            Desktop.getDesktop().browse(URI.create(target));
        } catch (Exception e) {
            Platform.runLater(() -> { });
        }
    }

    private void unloadPlayer() {
        if (player != null) {
            try {
                player.stop();
                player.dispose();
            } catch (RuntimeException ignored) {
                // disposing a half-ready player is best effort
            }
            player = null;
        }
        videoView.setMediaPlayer(null);
        playBtn.setText("▶");
    }

    /** Direct local files and http media streams play embedded; the rest is external. */
    private static boolean isPlayable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase();
        if (lower.startsWith("http")) {
            return lower.matches(".*\\.(mp3|mp4|m4a|wav|m4v|aac)(\\?.*)?$");
        }
        String path = lower.startsWith("file:") ? url.substring(5) : url;
        return Files.isRegularFile(Path.of(path));
    }

    private static String mediaUri(String url) {
        if (url.toLowerCase().startsWith("http") || url.toLowerCase().startsWith("file:")) {
            return url;
        }
        return Path.of(url).toUri().toString();
    }

    private static String fmt(double seconds) {
        int s = (int) Math.floor(Math.max(0, seconds));
        return "%02d:%02d:%02d".formatted(s / 3600, (s % 3600) / 60, s % 60);
    }
}
