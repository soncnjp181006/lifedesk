package com.lifedesk.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lifedesk.config.AiConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Ollama local LLM provider (http://localhost:11434 by default).
 * Native endpoints: GET /api/tags (models), POST /api/chat, POST /api/pull
 * (explicit, user-initiated model download with progress; the app never
 * downloads a model on its own).
 */
public class OllamaProvider implements AiProvider {
    public static final String ID = "ollama";

    private final AiConfig.Ollama settings;

    public OllamaProvider(AiConfig.Ollama settings) {
        this.settings = settings;
    }

    private String base() {
        String url = settings.baseUrl == null || settings.baseUrl.isBlank()
                ? "http://localhost:11434" : settings.baseUrl.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Ollama (local)";
    }

    @Override
    public boolean isAvailable() {
        try {
            return AiHttp.get(base() + "/api/tags", null, 3).ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<ModelInfo> listModels() {
        AiHttp.Response resp = AiHttp.get(base() + "/api/tags", null, Math.max(5, settings.timeoutSeconds));
        if (!resp.ok()) {
            throw new AiException("Ollama trả về HTTP " + resp.status());
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        List<ModelInfo> out = new ArrayList<>();
        if (root.has("models") && root.get("models").isJsonArray()) {
            for (var el : root.getAsJsonArray("models")) {
                JsonObject m = el.getAsJsonObject();
                String name = m.has("name") ? m.get("name").getAsString() : "?";
                String size = m.has("size") ? humanBytes(m.get("size").getAsLong()) : "";
                String details = m.has("details") && m.getAsJsonObject("details").has("parameter_size")
                        ? m.getAsJsonObject("details").get("parameter_size").getAsString() : "";
                out.add(new ModelInfo(name, ID, (size + " " + details).trim()));
            }
        }
        return out;
    }

    static String humanBytes(long bytes) {
        double b = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (b >= 1024 && u < units.length - 1) {
            b /= 1024;
            u++;
        }
        return String.format("%.1f%s", b, units[u]);
    }

    @Override
    public String chat(List<ChatMessage> messages, String model) {
        String modelId = model == null || model.isBlank() ? settings.model : model;
        if (modelId == null || modelId.isBlank()) {
            throw new AiException("Chưa chọn model Ollama trong Cài đặt → AI");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("stream", false);
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject jm = new JsonObject();
            jm.addProperty("role", m.role());
            jm.addProperty("content", m.content());
            msgs.add(jm);
        }
        body.add("messages", msgs);
        AiHttp.Response resp = AiHttp.postJson(base() + "/api/chat", body.toString(), null, settings.timeoutSeconds);
        if (!resp.ok()) {
            throw new AiException("Ollama trả về HTTP " + resp.status() + ": " + truncate(resp.body()));
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.has("error")) {
            throw new AiException("Ollama lỗi: " + root.get("error").getAsString());
        }
        if (root.has("message") && root.getAsJsonObject("message").has("content")) {
            return root.getAsJsonObject("message").get("content").getAsString();
        }
        throw new AiException("Phản hồi Ollama không đúng định dạng");
    }

    /**
     * Explicit model pull with streaming progress. Called only from a user
     * action (button) — the app never pulls models automatically.
     *
     * @param onProgress receives short status lines
     * @return final status message
     */
    public String pullModel(String name, java.util.function.Consumer<String> onProgress) {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        body.addProperty("stream", true);
        AiHttp.Response resp;
        try {
            var uri = java.net.URI.create(base() + "/api/pull");
            var request = java.net.http.HttpRequest.newBuilder(uri)
                    .timeout(java.time.Duration.ofMinutes(30))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            var httpResponse = java.net.http.HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofLines());
            if (httpResponse.statusCode() != 200) {
                throw new AiException("Ollama pull trả về HTTP " + httpResponse.statusCode());
            }
            StringBuilder finalStatus = new StringBuilder();
            httpResponse.body().forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                    if (o.has("error")) {
                        throw new AiException("Lỗi tải model: " + o.get("error").getAsString());
                    }
                    String status = o.has("status") ? o.get("status").getAsString() : "";
                    if (o.has("total") && o.has("completed") && o.get("total").getAsLong() > 0) {
                        long total = o.get("total").getAsLong();
                        long done = o.get("completed").getAsLong();
                        status = status + " " + (done * 100 / total) + "% (" + humanBytes(done) + "/" + humanBytes(total) + ")";
                    }
                    if (o.has("done") && o.get("done").getAsBoolean()) {
                        finalStatus.append("Hoàn tất tải ").append(name);
                    }
                    if (!status.isBlank()) {
                        onProgress.accept(status);
                    }
                } catch (AiException e) {
                    throw e;
                } catch (RuntimeException ignored) {
                    // skip malformed progress lines
                }
            });
            return finalStatus.length() > 0 ? finalStatus.toString() : "Đã yêu cầu tải model " + name;
        } catch (java.io.IOException e) {
            throw new AiException("Lỗi kết nối khi tải model: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Tải model bị hủy");
        }
    }

    @Override
    public boolean supportsModelListing() {
        return true;
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(300, s.length()));
    }
}
