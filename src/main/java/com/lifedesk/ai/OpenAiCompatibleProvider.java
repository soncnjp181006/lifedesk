package com.lifedesk.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lifedesk.config.AiConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible cloud provider, used for Kilo
 * (base URL / endpoint paths and API key are user-configured in Settings).
 * Requests are only made when the user sends a chat or browses models —
 * LifeDesk never uploads user data by itself.
 */
public class OpenAiCompatibleProvider implements AiProvider {
    public static final String ID = "kilo";

    private final AiConfig.Kilo settings;

    public OpenAiCompatibleProvider(AiConfig.Kilo settings) {
        this.settings = settings;
    }

    private String base() {
        String url = settings.baseUrl == null || settings.baseUrl.isBlank()
                ? AiConfig.Kilo.DEFAULT_BASE_URL : settings.baseUrl.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String auth() {
        if (settings.apiKey == null || settings.apiKey.isBlank()) {
            throw new AiException("Chưa nhập API key cho Kilo (Cài đặt → AI)");
        }
        return "Bearer " + settings.apiKey.trim();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Kilo (cloud, OpenAI-compatible)";
    }

    @Override
    public boolean isAvailable() {
        try {
            return AiHttp.get(base() + path(settings.modelsPath, "/models"), auth(), 8).ok();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public List<ModelInfo> listModels() {
        AiHttp.Response resp = AiHttp.get(base() + path(settings.modelsPath, "/models"), auth(),
                Math.max(10, settings.timeoutSeconds));
        if (resp.status() == 401 || resp.status() == 403) {
            throw new AiException("API key không hợp lệ hoặc thiếu quyền (HTTP " + resp.status() + ")");
        }
        if (!resp.ok()) {
            throw new AiException("Kilo trả về HTTP " + resp.status() + ": " + truncate(resp.body()));
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        List<ModelInfo> out = new ArrayList<>();
        JsonArray models = root.has("data") && root.get("data").isJsonArray()
                ? root.getAsJsonArray("data")
                : root.has("models") && root.get("models").isJsonArray() ? root.getAsJsonArray("models") : null;
        if (models == null) {
            throw new AiException("Phản hồi danh sách model không đúng định dạng");
        }
        for (JsonElement el : models) {
            if (el.isJsonObject()) {
                JsonObject m = el.getAsJsonObject();
                String id = m.has("id") ? m.get("id").getAsString()
                        : m.has("name") ? m.get("name").getAsString() : "?";
                String details = m.has("context_length") ? "ctx " + m.get("context_length").getAsString() : "";
                out.add(new ModelInfo(id, ID, details));
            } else if (el.isJsonPrimitive()) {
                out.add(new ModelInfo(el.getAsString(), ID, ""));
            }
        }
        return out;
    }

    @Override
    public String chat(List<ChatMessage> messages, String model) {
        String modelId = model == null || model.isBlank() ? settings.model : model;
        if (modelId == null || modelId.isBlank()) {
            throw new AiException("Chưa chọn model Kilo trong Cài đặt → AI");
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
        AiHttp.Response resp = AiHttp.postJson(base() + path(settings.chatPath, "/chat/completions"),
                body.toString(), auth(), settings.timeoutSeconds);
        if (resp.status() == 401 || resp.status() == 403) {
            throw new AiException("API key không hợp lệ (HTTP " + resp.status() + ")");
        }
        if (!resp.ok()) {
            throw new AiException("Kilo trả về HTTP " + resp.status() + ": " + truncate(resp.body()));
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (root.has("error")) {
            JsonObject err = root.getAsJsonObject("error");
            throw new AiException("Kilo lỗi: " + (err.has("message") ? err.get("message").getAsString() : err.toString()));
        }
        if (root.has("choices") && root.getAsJsonArray("choices").size() > 0) {
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (choice.has("message") && choice.getAsJsonObject("message").has("content")) {
                return choice.getAsJsonObject("message").get("content").getAsString();
            }
        }
        throw new AiException("Phản hồi Kilo không đúng định dạng");
    }

    /**
     * Streaming chat: OpenAI SSE protocol — lines "data: {json}" carrying
     * choices[0].delta.content, terminated by "data: [DONE]".
     */
    @Override
    public String chatStream(List<ChatMessage> messages, String model, java.util.function.Consumer<String> onDelta) {
        String modelId = model == null || model.isBlank() ? settings.model : model;
        if (modelId == null || modelId.isBlank()) {
            throw new AiException("Chưa chọn model Kilo trong Cài đặt → AI");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("stream", true);
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : messages) {
            JsonObject jm = new JsonObject();
            jm.addProperty("role", m.role());
            jm.addProperty("content", m.content());
            msgs.add(jm);
        }
        body.add("messages", msgs);
        try {
            var request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(base() + path(settings.chatPath, "/chat/completions")))
                    .timeout(java.time.Duration.ofSeconds(Math.max(30, settings.timeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", auth())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            var httpResponse = java.net.http.HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofLines());
            if (httpResponse.statusCode() == 401 || httpResponse.statusCode() == 403) {
                throw new AiException("API key không hợp lệ (HTTP " + httpResponse.statusCode() + ")");
            }
            if (httpResponse.statusCode() != 200) {
                throw new AiException("Kilo trả về HTTP " + httpResponse.statusCode());
            }
            StringBuilder full = new StringBuilder();
            try (var lines = httpResponse.body()) {
                for (java.util.Iterator<String> it = lines.iterator(); it.hasNext(); ) {
                    String line = it.next().trim();
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonObject o = JsonParser.parseString(data).getAsJsonObject();
                    if (o.has("error")) {
                        JsonObject err = o.getAsJsonObject("error");
                        throw new AiException("Kilo lỗi: " + (err.has("message")
                                ? err.get("message").getAsString() : err.toString()));
                    }
                    if (o.has("choices") && o.getAsJsonArray("choices").size() > 0) {
                        JsonObject choice = o.getAsJsonArray("choices").get(0).getAsJsonObject();
                        if (choice.has("delta") && choice.getAsJsonObject("delta").has("content")) {
                            String delta = choice.getAsJsonObject("delta").get("content").getAsString();
                            if (!delta.isEmpty()) {
                                full.append(delta);
                                onDelta.accept(delta);
                            }
                        }
                    }
                }
            }
            return full.toString();
        } catch (java.io.IOException e) {
            throw new AiException("Lỗi kết nối Kilo: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Streaming bị hủy");
        }
    }

    @Override
    public boolean supportsModelListing() {
        return true;
    }

    private String path(String configured, String fallback) {
        String p = configured == null || configured.isBlank() ? fallback : configured;
        return p.startsWith("/") ? p : "/" + p;
    }

    private static String truncate(String s) {
        return s == null ? "" : s.substring(0, Math.min(300, s.length()));
    }
}
