package com.lifedesk.search;

/** A scored search hit. */
public record SearchResult(SearchDoc doc, int score) {
}
