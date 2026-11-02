package com.lifedesk.ui;

import com.lifedesk.app.AppContext;

import java.nio.file.Path;

/**
 * The user-consent flow for granting LifeDesk read access to a folder
 * (file manager + agent file tools). One implementation so the security
 * behavior — explicit chooser + confirmation + config update — is identical
 * from the Settings screen and the Files view.
 */
public final class RootGrants {
    private RootGrants() {
    }

    /**
     * Show a folder chooser and, on confirmation, add the folder to the
     * allowed roots. {@code confirmTemplate} is a {@link String#format}
     * template whose {@code %s} receives the chosen absolute path. Returns
     * the granted path, or null when cancelled. Persisting config afterwards
     * is the caller's job (it also refreshes its own UI).
     *
     * <p>The returned path is normalised because that is what the service stores:
     * comparing a chooser's raw string against {@code allowedRoots()} used to fail on
     * paths that differ only by a separator, and the caller then refreshed to a
     * different folder than the one the user had just confirmed.
     */
    public static Path grantViaDialog(AppContext ctx, MainWindow main, String chooserTitle,
            String confirmTitle, String confirmTemplate) {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(chooserTitle);
        java.io.File dir = chooser.showDialog(main.stage());
        if (dir == null) {
            return null;
        }
        Path granted = dir.toPath().toAbsolutePath().normalize();
        if (!Dialogs.confirm(confirmTitle, String.format(confirmTemplate, granted))) {
            return null;
        }
        if (!ctx.config().allowedRoots.contains(granted.toString())) {
            ctx.config().allowedRoots.add(granted.toString());
        }
        ctx.files().setAllowedRoots(ctx.config().allowedRoots);
        return granted;
    }
}
