package com.lifedesk.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lifedesk.common.AppException;
import com.lifedesk.storage.JsonStore;
import com.lifedesk.storage.SchemaMigrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads and persists the application configuration.
 *
 * The config lives in a well-known per-user location
 * ({@code %APPDATA%/LifeDesk/config} on Windows, overridable via the
 * {@code lifedesk.config} system property) and points at the data directory,
 * so user data is never inside the installation folder.
 */
public class ConfigService {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigService.class);
    public static final int SCHEMA_VERSION = 1;

    private final Path configDir;
    private final JsonStore store;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final SchemaMigrator MIGRATOR = new SchemaMigrator() {
        @Override
        public int currentVersion() {
            return SCHEMA_VERSION;
        }

        @Override
        public JsonObject migrate(JsonObject data, int fromVersion) {
            // v0 -> v1: no structural change yet; placeholders keep future migrations explicit.
            return data;
        }
    };

    public ConfigService(Path configDir) {
        this.configDir = configDir.toAbsolutePath().normalize();
        this.store = new JsonStore(this.configDir.resolve("config"), new SilentIssueLog());
    }

    public Path configDir() {
        return configDir;
    }

    public AppConfig load() {
        JsonStore.StoredDoc doc = store.read("app-config.json", MIGRATOR);
        if (doc == null) {
            LOG.info("No config found; creating default config in {}", configDir);
            AppConfig config = new AppConfig();
            config.dataDir = defaultDataDir();
            save(config);
            return config;
        }
        AppConfig config = gson.fromJson(doc.data(), AppConfig.class);
        if (config == null) {
            config = new AppConfig();
        }
        if (config.dataDir == null || config.dataDir.isBlank()) {
            config.dataDir = defaultDataDir();
        }
        // Earlier builds defaulted Kilo to api.kilo.ai/v1, which 404s on /models.
        // Only rewrite that exact legacy default - a URL the user typed is kept.
        if (config.ai != null && config.ai.kilo != null) {
            String base = config.ai.kilo.baseUrl;
            if (base == null || base.isBlank() || "https://api.kilo.ai/v1".equals(base)) {
                config.ai.kilo.baseUrl = AiConfig.Kilo.DEFAULT_BASE_URL;
            }
        }
        return config;
    }

    public void save(AppConfig config) {
        JsonObject data = (JsonObject) gson.toJsonTree(config);
        store.write("app-config.json", SCHEMA_VERSION, data);
        LOG.debug("Config saved");
    }

    public static Path defaultConfigDir() {
        String override = System.getProperty("lifedesk.config");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "LifeDesk", "config");
        }
        return Paths.get(System.getProperty("user.home"), ".lifedesk", "config");
    }

    public static String defaultDataDir() {
        String override = System.getProperty("lifedesk.data");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().toString();
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Paths.get(appData, "LifeDesk", "data").toAbsolutePath().toString();
        }
        return Paths.get(System.getProperty("user.home"), ".lifedesk", "data").toAbsolutePath().toString();
    }

    /** Ensures the data directory exists and is writable; throws with a clear message otherwise. */
    public static Path ensureDataDir(String path) {
        Path p = Paths.get(path).toAbsolutePath().normalize();
        try {
            Files.createDirectories(p);
            Path probe = p.resolve(".write-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
        } catch (Exception e) {
            throw new AppException("Không thể ghi vào thư mục dữ liệu: " + p + "\n" + e.getMessage(), e);
        }
        return p;
    }

    /** Issue log that only logs to slf4j (config store problems are shown in the UI as launch errors). */
    static final class SilentIssueLog extends com.lifedesk.storage.StorageIssueLog {
        @Override
        public void report(com.lifedesk.storage.StorageIssue issue) {
            LoggerFactory.getLogger(ConfigService.class)
                    .warn("Config store issue [{}] {}: {}", issue.path(), issue.severity(), issue.message());
        }
    }
}
