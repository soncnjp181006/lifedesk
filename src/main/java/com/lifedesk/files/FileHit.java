package com.lifedesk.files;

import java.nio.file.Path;
import java.time.Instant;

/** Metadata of one file/folder hit in the file manager. */
public record FileHit(Path path, String name, boolean directory, long sizeBytes,
                      Instant lastModified, String extension) {
}
