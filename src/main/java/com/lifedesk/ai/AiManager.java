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
            messages.add(ChatMessage.system(systemPrompt()));
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

    /**
     * Persona + answer contract. Kept explicit about markdown and about not
     * inventing numbers: the reply is rendered as a document in the chat, so a
     * well-structured answer is directly readable, and a confident-but-made-up
     * total is the worst thing a personal assistant can do.
     */
    private String systemPrompt() {
        java.time.LocalDate today = java.time.LocalDate.now();
        String weekday = today.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("vi-VN"));
        return "Bạn là trợ lý năng suất cá nhân trong ứng dụng LifeDesk của người dùng Việt Nam.\n"
                + "- Trả lời bằng tiếng Việt, ngắn gọn nhưng cụ thể; tối đa 3 gợi ý hành động mỗi lần.\n"
                + "- Luôn định dạng bằng Markdown: ## cho đề mục, - cho gạch đầu dòng, **đậm** cho ý chính,"
                + " bảng khi so sánh số liệu, ``` khi viết code.\n"
                + "- Chỉ dùng số liệu có trong dữ liệu được cung cấp. Nếu thiếu dữ liệu, nói rõ là chưa ghi nhận,"
                + " tuyệt đối không bịa con số.\n"
                + "- Hôm nay là " + weekday + ", " + today.format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")) + ".\n"
                + "- Khi được hỏi nên làm gì, hãy xếp hạng việc theo mức độ gấp và nêu lý do trong một dòng.";
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
