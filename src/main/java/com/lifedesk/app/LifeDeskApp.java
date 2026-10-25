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

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        try {
            ConfigService configService = new ConfigService(ConfigService.defaultConfigDir());
            AppConfig config = configService.load();
            Path dataDir = ConfigService.ensureDataDir(config.dataDir);
            // Re-check the system property override (smoke tests) after config load.
            String propData = System.getProperty("lifedesk.data");
            if (propData != null && !propData.isBlank()) {
                dataDir = ConfigService.ensureDataDir(propData);
                config.dataDir = dataDir.toString();
            }
            dataLock = SingleInstanceLock.acquire(dataDir);
            context = new AppContext(dataDir, config);
            LOG.info("LifeDesk starting; data dir: {}", dataDir);
        } catch (Exception e) {
            LOG.error("Startup failed", e);
            // rethrow so JavaFX reports the failure and exits
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public void start(Stage stage) {
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
