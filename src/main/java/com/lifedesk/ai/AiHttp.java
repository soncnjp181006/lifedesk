package com.lifedesk.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Shared HTTP plumbing for AI providers (java.net.http, no extra dependency). */
final class AiHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private AiHttp() {
    }

    record Response(int status, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    static Response get(String url, String authHeader, int timeoutSeconds) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .GET();
        if (authHeader != null && !authHeader.isBlank()) {
            b.header("Authorization", authHeader);
        }
        return send(b);
    }

    static Response postJson(String url, String json, String authHeader, int timeoutSeconds) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authHeader != null && !authHeader.isBlank()) {
            b.header("Authorization", authHeader);
        }
        return send(b);
    }

    private static Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> resp = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(resp.statusCode(), resp.body());
        } catch (java.io.IOException e) {
            throw new AiException("Lỗi kết nối: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException("Yêu cầu bị hủy", e);
        }
    }
}
