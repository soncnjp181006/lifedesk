package com.lifedesk.storage;

import com.google.gson.JsonObject;

/**
 * Per-store schema migration. Implementations migrate a raw JSON payload
 * step by step from {@code fromVersion} up to {@link #currentVersion()}.
 */
public interface SchemaMigrator {
    int currentVersion();

    JsonObject migrate(JsonObject data, int fromVersion);

    /** Convenience helper for the common no-migration case. */
    static SchemaMigrator none(int version) {
        return new SchemaMigrator() {
            @Override
            public int currentVersion() {
                return version;
            }

            @Override
            public JsonObject migrate(JsonObject data, int fromVersion) {
                return data;
            }
        };
    }
}
