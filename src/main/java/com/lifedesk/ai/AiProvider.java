package com.lifedesk.ai;

import java.util.List;

/**
 * Pluggable AI backend. Implementations: rule-based engine (offline, no LLM),
 * Ollama (local models) and OpenAI-compatible cloud providers (Kilo).
 */
public interface AiProvider {
    /** Stable id used in config: "rule-based" | "ollama" | "kilo". */
    String id();

    String displayName();

    /** Quick availability probe (cheap request, short timeout). Never throws. */
    boolean isAvailable();

    /** List models the backend currently offers. Throws AiException on failure. */
    List<ModelInfo> listModels();

    /** Run one chat completion. Throws AiException on failure. */
    String chat(List<ChatMessage> messages, String model);

    /**
     * Stream one completion: {@code onDelta} receives incremental text chunks
     * on the calling thread (a background executor). Returns the full reply.
     * Default: non-streaming chat delivered as one chunk (providers without
     * stream support, e.g. the rule engine).
     */
    default String chatStream(List<ChatMessage> messages, String model,
                              java.util.function.Consumer<String> onDelta) {
        String reply = chat(messages, model);
        onDelta.accept(reply);
        return reply;
    }

    /** True when the provider can enumerate its models (Ollama/Kilo) — used by the model browser. */
    boolean supportsModelListing();
}
