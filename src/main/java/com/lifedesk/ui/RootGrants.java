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
     */
    public static Path grantViaDialog(AppContext ctx, MainWindow main, String chooserTitle, String confirmTemplate) {
        javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
        chooser.setTitle(chooserTitle);
        java.io.File dir = chooser.showDialog(main.stage());
        if (dir == null) {
            return null;
        }
        if (!Dialogs.confirm("Cấp quyền", String.format(confirmTemplate, dir.getAbsolutePath()))) {
            return null;
        }
        ctx.config().allowedRoots.add(dir.getAbsolutePath());
        ctx.files().setAllowedRoots(ctx.config().allowedRoots);
        return dir.toPath();
    }
}
