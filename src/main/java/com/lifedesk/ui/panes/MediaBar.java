package com.lifedesk.ui.panes;

import com.lifedesk.ui.components.IconView;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
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
    /** Same words for every degrade cause: what the user can do is the same in each. */
    private static final String DEGRADE_NOTE = "không phát trong app — bấm Mở ngoài";

    private final Button playBtn = new Button("", new IconView("play", 15));
    private final Button timestampBtn = new Button("", new IconView("timer", 15));
    private final Button externalBtn = new Button("", new IconView("external-link", 15));
    private final Button removeBtn = new Button("Gỡ media");
    private final Slider seek = new Slider(0, 1, 0);
    private final Label timeLabel = new Label("00:00 / 00:00");
    private final Label urlLabel = new Label();
    /** Why the transport is off, on its own line: eliding it with the link would hide the advice. */
    private final Label hintLabel = new Label();
    private final MediaView videoView = new MediaView();

    private MediaPlayer player;
    private String noteId = "";
    private String currentUrl = "";
    private boolean externalOnly;
    private boolean seeking;
    private final java.util.function.Consumer<String> onTimestamp;

    /** @param onInsertTimestamp receives the "[HH:MM:SS]()" text for the editor caret */
    public MediaBar(java.util.function.Consumer<String> onInsertTimestamp, Runnable onRemove) {
        this.onTimestamp = onInsertTimestamp == null ? s -> {
        } : onInsertTimestamp;
        setSpacing(6);
        getStyleClass().add("card");

        // Icon-only transport: in the split editor the note column is barely 500px
        // wide, and a row of labelled buttons was shaved to "Chèn tim…" at the app's
        // own minimum window. The words move to the accessible name and the tooltip,
        // which is what a screen reader and a hover both read anyway.
        playBtn.getStyleClass().add("primary-btn");
        playBtn.setAccessibleText("Phát / tạm dừng");
        playBtn.setTooltip(new Tooltip("Phát / tạm dừng"));
        playBtn.setOnAction(e -> togglePlay());
        timestampBtn.getStyleClass().add("ghost-btn");
        timestampBtn.setAccessibleText("Chèn timestamp");
        timestampBtn.setTooltip(new Tooltip("Chèn [HH:MM:SS]() tại con trỏ — Ctrl+I"));
        timestampBtn.setOnAction(e -> insertTimestamp());
        externalBtn.getStyleClass().add("ghost-btn");
        externalBtn.setAccessibleText("Mở ngoài");
        externalBtn.setTooltip(new Tooltip("Mở media bằng trình duyệt/hệ thống"));
        externalBtn.setOnAction(e -> openExternal());
        externalBtn.setVisible(false);
        externalBtn.setManaged(false);
        // Not icon-btn: that class clamps the box to a 34px square, which had been
        // shaving this word to "Gỡ…" even in a wide window.
        removeBtn.getStyleClass().add("ghost-btn");
        removeBtn.setTooltip(new Tooltip("Gỡ liên kết media khỏi ghi chú"));
        removeBtn.setOnAction(e -> onRemove.run());

        seek.setPrefWidth(320);
        // The slider is the elastic part: a seek bar at 60px still works, a button
        // label at 60px does not.
        seek.setMinWidth(60);
        seek.setMaxWidth(Double.MAX_VALUE);
        seek.setDisable(true);
        seek.valueProperty().addListener((o, was, now) -> {
            if (seeking && player != null) {
                player.seek(Duration.seconds(now.doubleValue()));
            }
        });
        // One listener for the bar's whole life: attaching it per load() would stack a
        // new listener on the same slider every time a note is opened.
        seek.valueChangingProperty().addListener((o, was, changing) -> seeking = changing);

        // A FlowPane, not an HBox: the note column in the split editor is about 500px
        // and a single-row control strip would set the whole view's minimum width, so
        // the window would scroll sideways. The strip wraps to a second line instead,
        // and each control keeps every letter.
        FlowPane controls = new FlowPane(8, 6,
                playBtn, seek, timeLabel, timestampBtn, externalBtn, removeBtn);
        controls.setAlignment(Pos.CENTER_LEFT);
        // The strip wraps, so it must not be the thing that sets the note column's
        // minimum width: measured, its one-row floor (362px) pushed the whole split
        // past the viewport at the app's own minimum window. Min 0 lets the parent
        // narrow it and the FlowPane rewraps; each control inside keeps every letter.
        controls.setMinWidth(0);
        setMinWidth(0);
        for (Button b : new Button[]{playBtn, timestampBtn, externalBtn, removeBtn}) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        timeLabel.setMinWidth(Region.USE_PREF_SIZE);

        urlLabel.getStyleClass().add("dim-label");
        // The link line may be shortened: it is a Label with a tooltip carrying the
        // whole value, and a long path with no spaces would otherwise set the floor.
        urlLabel.setMinWidth(0);
        urlLabel.setMaxWidth(Double.MAX_VALUE);
        hintLabel.getStyleClass().add("dim-label");
        hintLabel.setWrapText(true);
        hintLabel.setMinWidth(0);
        hintLabel.setMaxWidth(Double.MAX_VALUE);
        videoView.setFitWidth(320);
        videoView.setFitHeight(180);
        videoView.setPreserveRatio(true);

        getChildren().addAll(videoView, urlLabel, controls, hintLabel);
        setMaxWidth(Double.MAX_VALUE);
    }

    /** Attach the player to a note's media link; unplayable links go external-only. */
    public void load(String newNoteId, String url) {
        unload();
        this.noteId = newNoteId;
        this.currentUrl = url == null ? "" : url.trim();
        // A full absolute path is wider than the bar and gets elided to "…".
        // Show the tail (the part that identifies the file) with the whole value a hover away.
        urlLabel.setText(com.lifedesk.ui.Fx.shortenPath(currentUrl));
        urlLabel.setTooltip(new Tooltip(currentUrl));
        hintLabel.setText("");
        if (!isPlayable(currentUrl)) {
            showExternalOnly(DEGRADE_NOTE);
            return;
        }
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
                player.play();
                setPlaying(true);
            });
            player.currentTimeProperty().addListener((o, was, now) -> {
                if (!seek.isValueChanging() && !seeking) {
                    double t = now.toSeconds();
                    seek.setValue(t);
                    timeLabel.setText(fmt(t) + " / " + fmt(seek.getMax()));
                }
            });
            player.setOnError(() -> showExternalOnly(DEGRADE_NOTE));
            player.setOnEndOfMedia(() -> setPlaying(false));
        } catch (RuntimeException e) {
            showExternalOnly(DEGRADE_NOTE);
        }
    }

    /**
     * The one degrade path: hide the transport, offer the OS handler instead.
     * Used by an unrecognised link, a player error, and a Media that will not construct.
     * The reason goes on the link line, which can wrap; the clock keeps its narrow
     * column because a squeezed "00:0…" is worse than a dash.
     */
    private void showExternalOnly(String reason) {
        unloadPlayer();          // stop + dispose whatever half-ready player exists
        externalOnly = true;
        videoView.setVisible(false);
        videoView.setManaged(false);
        playBtn.setDisable(true);
        timestampBtn.setDisable(true);
        seek.setDisable(true);
        externalBtn.setVisible(true);
        externalBtn.setManaged(true);
        timeLabel.setText("—");
        hintLabel.setText(reason);
    }

    private void setPlaying(boolean playing) {
        playBtn.setGraphic(new IconView(playing ? "pause" : "play", 15));
    }

    /** Stop playback and remember the resume position. */
    public void unload() {
        if (player != null && !currentUrl.isBlank()) {
            RESUME.put(noteId, player.getCurrentTime().toSeconds());
        }
        unloadPlayer();
        externalOnly = false;
        videoView.setVisible(false);
        videoView.setManaged(false);
        playBtn.setDisable(false);
        timestampBtn.setDisable(false);
        seek.setDisable(true);
        externalBtn.setVisible(false);
        externalBtn.setManaged(false);
        timeLabel.setText("00:00 / 00:00");
    }

    public boolean hasPlayer() {
        return player != null;
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
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
            setPlaying(false);
        } else {
            player.play();
            setPlaying(true);
        }
    }

    private void openExternal() {
        try {
            String target = currentUrl.startsWith("http") ? currentUrl
                    : Path.of(currentUrl).toUri().toString();
            Desktop.getDesktop().browse(URI.create(target));
        } catch (Exception e) {
            // Say so: a button that silently does nothing reads as a broken app.
            hintLabel.setText("không mở được liên kết bằng trình duyệt");
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
        setPlaying(false);
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
