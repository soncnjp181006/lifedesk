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

    /** True when the provider can enumerate its models (Ollama/Kilo) — used by the model browser. */
    boolean supportsModelListing();
}
