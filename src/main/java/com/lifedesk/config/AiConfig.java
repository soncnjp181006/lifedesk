package com.lifedesk.config;

/** AI provider settings. API keys are stored locally only, never sent anywhere except the configured endpoint. */
public class AiConfig {
    public boolean enabled = true;
    /** Active provider id: "rule-based" | "ollama" | "kilo". */
    public String providerId = "rule-based";
    public boolean includeUserData = true;
    public int maxHistoryMessages = 12;

    public Ollama ollama = new Ollama();
    public Kilo kilo = new Kilo();

    public static class Ollama {
        public String baseUrl = "http://localhost:11434";
        public String model = "";
        public int timeoutSeconds = 120;
    }

    public static class Kilo {
        /** OpenAI-compatible base URL; endpoint paths may be adjusted here too. */
        public String baseUrl = "https://api.kilo.ai/v1";
        public String modelsPath = "/models";
        public String chatPath = "/chat/completions";
        public String apiKey = "";
        public String model = "";
        public int timeoutSeconds = 120;
    }
}
