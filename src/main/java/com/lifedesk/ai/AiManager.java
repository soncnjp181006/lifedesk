package com.lifedesk.ai;

import com.lifedesk.config.AiConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI facade: provider selection, availability checks and async chat.
 * When AI is disabled in settings, {@link #currentProvider()} is empty and
 * callers must degrade gracefully — nothing else in the app depends on AI.
 */
public class AiManager {
    private final AiConfig config;
    private final RuleBasedEngine ruleEngine;
    private final OllamaProvider ollama;
    private final OpenAiCompatibleProvider kilo;

    public AiManager(AiConfig config, RuleBasedEngine ruleEngine) {
        this.config = config;
        this.ruleEngine = ruleEngine;
        this.ollama = new OllamaProvider(config.ollama);
        this.kilo = new OpenAiCompatibleProvider(config.kilo);
    }

    public List<AiProvider> providers() {
        return List.of(ruleEngine, ollama, kilo);
    }

    public AiProvider byId(String id) {
        for (AiProvider p : providers()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return ruleEngine;
    }

    /** The provider currently selected in settings; empty when AI is disabled. */
    public java.util.Optional<AiProvider> currentProvider() {
        if (!config.enabled) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(byId(config.providerId));
    }

    public boolean enabled() {
        return config.enabled;
    }

    public void setEnabled(boolean enabled) {
        config.enabled = enabled;
    }

    /** Chat on a caller-provided executor; callbacks run on that thread too. */
    public void chatAsync(java.util.concurrent.Executor executor,
                          List<ChatMessage> history, String userMessage,
                          Consumer<String> onDone, Consumer<Throwable> onError) {
        executor.execute(() -> {
            try {
                List<ChatMessage> messages = buildMessages(history, userMessage);
                AiProvider provider = currentProvider().orElseThrow(
                        () -> new AiException("AI đang tắt trong Cài đặt."));
                String reply = provider.chat(messages, activeModel());
                onDone.accept(reply);
            } catch (Throwable t) {
                onError.accept(t);
            }
        });
    }

    /**
     * Streaming chat: {@code onDelta} receives incremental chunks (background
     * thread), {@code onDone} the full reply, {@code onError} any failure.
     */
    public void chatStreamAsync(java.util.concurrent.Executor executor,
                                List<ChatMessage> history, String userMessage,
                                Consumer<String> onDelta, Consumer<String> onDone,
                                Consumer<Throwable> onError) {
        executor.execute(() -> {
            try {
                List<ChatMessage> messages = buildMessages(history, userMessage);
                AiProvider provider = currentProvider().orElseThrow(
                        () -> new AiException("AI đang tắt trong Cài đặt."));
                String reply = provider.chatStream(messages, activeModel(), onDelta);
                onDone.accept(reply);
            } catch (Throwable t) {
                onError.accept(t);
            }
        });
    }

    /** Built-in prompt templates for the "/" menu in the chat input. */
    public record PromptTemplate(String name, String body) {
    }

    public static List<PromptTemplate> promptTemplates() {
        return List.of(
                new PromptTemplate("/kehoach", "Giúp tôi lập kế hoạch làm việc cho hôm nay dựa trên dữ liệu: việc đến hạn, thói quen, và thời gian trống."),
                new PromptTemplate("/chitieu", "Phân tích chi tiêu tháng này và gợi ý 3 cách tiết kiệm cụ thể."),
                new PromptTemplate("/tomtat", "Tóm tắt nội dung sau thành 5 gạch đầu dòng: "),
                new PromptTemplate("/ontap", "Tạo 5 câu hỏi ôn tập từ các flashcard và khóa học của tôi, kèm đáp án."),
                new PromptTemplate("/email", "Viết một email ngắn gọn, chuyên nghiệp về chủ đề: "),
                new PromptTemplate("/latex", "Giải thích khái niệm toán học sau và viết công thức LaTeX: "));
    }

    private List<ChatMessage> buildMessages(List<ChatMessage> history, String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        boolean llm = config.providerId.equals(OllamaProvider.ID) || config.providerId.equals(OpenAiCompatibleProvider.ID);
        if (llm) {
            messages.add(ChatMessage.system(
                    "Bạn là trợ lý năng suất cá nhân của ứng dụng LifeDesk. Trả lời ngắn gọn, tiếng Việt. "
                            + "Khi được cung cấp dữ liệu người dùng, hãy dựa vào đó để trả lời."));
            if (config.includeUserData) {
                messages.add(ChatMessage.system(ruleEngine.contextSnapshot()));
            }
        }
        int max = Math.max(2, config.maxHistoryMessages);
        int from = Math.max(0, history.size() - max);
        for (int i = from; i < history.size(); i++) {
            messages.add(history.get(i));
        }
        messages.add(ChatMessage.user(userMessage));
        return messages;
    }

    /** Model configured for the active provider (empty string lets the provider default). */
    public String activeModel() {
        return switch (config.providerId) {
            case OllamaProvider.ID -> config.ollama.model;
            case OpenAiCompatibleProvider.ID -> config.kilo.model;
            default -> "rule-engine-1.0";
        };
    }
}
