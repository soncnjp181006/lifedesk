package com.lifedesk.security;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Append-only JSONL audit log for agent actions: one line per step with
 * timestamp, run/step ids, tool, SANITIZED parameters (secrets masked,
 * long content truncated), outcome and error. The log cannot be disabled
 * through the app: there is no code path that turns it off, and the agent
 * has no tool that can write to the security directory.
 */
public class AuditLogger {
    private static final Logger LOG = LoggerFactory.getLogger(AuditLogger.class);
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(api_?key|token|secret|password|authorization)");
    private static final int MAX_VALUE_LEN = 200;

    private final Path auditDir;

    public AuditLogger(Path dataDir) {
        this.auditDir = dataDir.resolve("automation");
        try {
            Files.createDirectories(auditDir);
        } catch (IOException e) {
            throw new com.lifedesk.common.StorageException("Không tạo được thư mục audit", e);
        }
    }

    public synchronized void log(String runId, String stepId, String tool, JsonObject params,
                                 String outcome, String message, long durationMs) {
        JsonObject entry = new JsonObject();
        entry.addProperty("ts", Instant.now().toString());
        entry.addProperty("runId", runId);
        entry.addProperty("stepId", stepId);
        entry.addProperty("tool", tool);
        entry.add("params", sanitize(params));
        entry.addProperty("outcome", outcome);
        entry.addProperty("message", truncate(message));
        entry.addProperty("durationMs", durationMs);

        String fileName = "audit-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM")) + ".jsonl";
        Path file = auditDir.resolve(fileName);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(entry.toString());
            w.newLine();
        } catch (IOException e) {
            // The audit failure must be loud in the app log, but must never crash a run.
            LOG.error("AUDIT WRITE FAILED for run {} step {} tool {}: {}", runId, stepId, tool, e.toString());
        }
    }

    /** Mask secret-ish fields, truncate long strings, strip nested noise. */
    public static JsonObject sanitize(JsonObject params) {
        JsonObject out = new JsonObject();
        if (params == null) {
            return out;
        }
        for (var e : params.entrySet()) {
            String key = e.getKey();
            if (SECRET_KEY.matcher(key).find()) {
                out.addProperty(key, "***");
                continue;
            }
            if (e.getValue() == null || e.getValue().isJsonNull()) {
                out.add(key, null);
            } else if (e.getValue().isJsonObject()) {
                out.add(key, sanitize(e.getValue().getAsJsonObject()));
            } else {
                String value = e.getValue().toString();
                out.addProperty(key, truncate(value));
            }
        }
        return out;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_VALUE_LEN ? s : s.substring(0, MAX_VALUE_LEN) + "…(truncated)";
    }
}
