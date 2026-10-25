package com.lifedesk.search;

import java.util.List;

/** Results grouped by document type for display. */
public record SearchGroup(String type, String displayType, List<SearchResult> results) {
}
