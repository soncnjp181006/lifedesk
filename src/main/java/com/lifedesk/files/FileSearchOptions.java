package com.lifedesk.files;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/** Options for a file search inside an allowed root. */
public class FileSearchOptions {
    public Path root;
    public String nameQuery = "";
    public Set<String> extensions = Set.of();
    public Long minSizeBytes;
    public Long maxSizeBytes;
    public Instant modifiedAfter;
    public Instant modifiedBefore;
    public boolean includeSubfolders = true;
    public int maxDepth = 6;
    public int maxResults = 500;
}
