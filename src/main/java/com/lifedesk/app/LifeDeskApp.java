package com.lifedesk.app;

import com.lifedesk.config.AppConfig;
import com.lifedesk.config.ConfigService;
import com.lifedesk.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JavaFX bootstrap: config load, data-dir lock, AppContext build, MainWindow.
 */
public class LifeDeskApp extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(LifeDeskApp.class);

    private AppContext context;
    private SingleInstanceLock dataLock;
    private MainWindow mainWindow;
    /** Set in start(); used by the UI smoke test to snapshot the real scene. */
    public static volatile Stage primaryStageRef;
    /** Set in start(); used by the UI smoke test to navigate between views. */
    public static volatile com.lifedesk.ui.MainWindow mainWindowRef;

    /** Set once so a repeating failure cannot stack dialogs on top of each other. */
    private static final java.util.concurrent.atomic.AtomicBoolean CRASH_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Never disappear silently: an exception in a UI callback used to end the
     * process with nothing but a console stack trace (which a windowed launch
     * does not even show). Now the error is logged and surfaced once.
     */
    private static void installCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            LOG.error("Uncaught exception on thread {}", thread.getName(), error);
            reportCrash(error);
        });
    }

    private static void reportCrash(Throwable error) {
        if (!CRASH_REPORTED.compareAndSet(false, true)) {
            return;
        }
        Runnable show = () -> {
            try {
                javafx.scene.control.Alert alert =
                        new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle("LifeDesk gặp lỗi");
                alert.setHeaderText("Một thao tác vừa gây lỗi — ứng dụng vẫn tiếp tục chạy");
                String detail = error.getMessage() == null ? error.toString() : error.getMessage();
                alert.setContentText(detail
                        + "\n\nDữ liệu của bạn vẫn được lưu. Chi tiết kỹ thuật nằm trong thư mục logs.");
                com.lifedesk.ui.Dialogs.style(alert);
                alert.show();
            } catch (RuntimeException ignored) {
                // UI not ready — the log entry is the record of the failure.
            }
        };
        try {
            if (Platform.isFxApplicationThread()) {
                show.run();
            } else {
                Platform.runLater(show);
            }
        } catch (IllegalStateException toolkitNotStarted) {
            LOG.warn("Crash before the JavaFX toolkit started: {}", error.toString());
        }
    }

    /** Startup failure dialog: must not depend on the JavaFX toolkit being alive. */
    private static void showStartupFailure(Throwable error) {
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? error.toString() : error.getMessage();
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        try {
            String logDir = System.getProperty("lifedesk.log.dir", "");
            javax.swing.JOptionPane.showMessageDialog(null,
                    "LifeDesk không khởi động được.\n\n" + message
                            + (logDir.isBlank() ? "" : "\n\nChi tiết kỹ thuật: " + logDir),
                    "LifeDesk — không thể khởi động",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // no display at all; the log entry is the record
        }
    }

    @Override
    public void init() {
        installCrashHandler();
        try {
            // The UI is Vietnamese throughout; without this DatePicker renders
            // US-style mm/dd/yyyy next to labels that say dd/MM.
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("vi-VN"));
            ConfigService configService = new ConfigService(ConfigService.defaultConfigDir());
            AppConfig config = configService.load();
            Path dataDir = ConfigService.ensureDataDir(config.dataDir);
            // Re-check the system property override (smoke tests). IMPORTANT: never
            // write the override into config - it would poison the next real launch.
            String propData = System.getProperty("lifedesk.data");
            if (propData != null && !propData.isBlank()) {
                dataDir = ConfigService.ensureDataDir(propData);
            }
            dataLock = SingleInstanceLock.acquire(dataDir);
            context = new AppContext(dataDir, config);
            LOG.info("LifeDesk starting; data dir: {}", dataDir);
        } catch (Exception e) {
            LOG.error("Startup failed", e);
            // A windowed launch (javaw / packaged exe) has no console: throwing here
            // makes the app vanish with nothing on screen — the classic "tự thoát app".
            // Show the reason with Swing (the JavaFX toolkit is not up yet), then exit
            // through JavaFX's normal failure path.
            showStartupFailure(e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void start(Stage stage) {
        try {
            startUi(stage);
        } catch (Throwable error) {
            LOG.error("UI start failed", error);
            showStartupFailure(error);
            Platform.exit();
        }
    }

    private void startUi(Stage stage) {
        primaryStageRef = stage;
        Scene scene = new Scene(new javafx.scene.layout.StackPane(), context.config().windowWidth,
                context.config().windowHeight);
        mainWindow = new MainWindow(stage, scene, context);
        mainWindowRef = mainWindow;
        scene.setRoot(mainWindow.root());
        stage.setScene(scene);
        applyWindowSettings(stage);
        stage.setTitle("LifeDesk — Trợ lý năng suất cá nhân");
        stage.setMinWidth(1080);
        stage.setMinHeight(680);
        stage.setOnCloseRequest(e -> saveWindowSettings(stage));
        stage.show();
        // Surface failures instead of a silent exit (the crash dialog re-arms per session).
        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> {
            LOG.error("Uncaught exception on the JavaFX thread", e);
            reportCrash(e);
        });
        context.startBackgroundServices();
        mainWindow.onReady();
    }

    private void applyWindowSettings(Stage stage) {
        if (context.config().windowMaximized) {
            stage.setMaximized(true);
        }
    }

    private void saveWindowSettings(Stage stage) {
        context.config().windowMaximized = stage.isMaximized();
        if (!stage.isMaximized() && stage.getWidth() > 0) {
            context.config().windowWidth = (int) stage.getWidth();
            context.config().windowHeight = (int) stage.getHeight();
        }
        try {
            new ConfigService(ConfigService.defaultConfigDir()).save(context.config());
        } catch (RuntimeException e) {
            LOG.warn("Could not save window settings: {}", e.toString());
        }
    }

    @Override
    public void stop() {
        try {
            if (context != null) {
                context.shutdown();
            }
        } finally {
            if (dataLock != null) {
                dataLock.close();
            }
            LOG.info("LifeDesk stopped");
            Platform.exit();
        }
    }
}
