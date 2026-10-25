package com.lifedesk.files;

import java.time.Instant;

/** A user-favorited folder in the file manager. */
public class FileFavorite {
    public String path;
    public String label = "";
    public Instant addedAt = Instant.now();

    public FileFavorite() {
    }

    public FileFavorite(String path, String label) {
        this.path = path;
        this.label = label;
    }
}
