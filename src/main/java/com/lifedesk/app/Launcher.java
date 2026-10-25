package com.lifedesk.app;

/** Entry point. JavaFX toolkit bootstrapping happens in LifeDeskApp. */
public final class Launcher {
    public static void main(String[] args) {
        // Log directory must exist before logback initializes its file appender.
        String base = System.getProperty("lifedesk.log.dir");
        if (base == null || base.isBlank()) {
            String appData = System.getenv("APPDATA");
            String root = appData != null && !appData.isBlank()
                    ? appData + java.io.File.separator + "LifeDesk"
                    : System.getProperty("user.home") + java.io.File.separator + ".lifedesk";
            System.setProperty("lifedesk.log.dir", root + java.io.File.separator + "logs");
        }
        LifeDeskApp.main(args);
    }

    private Launcher() {
    }
}
