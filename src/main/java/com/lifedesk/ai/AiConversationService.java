package com.lifedesk.ai;

import com.lifedesk.common.ChangeNotifier;
import com.lifedesk.storage.JsonStore;
import com.google.gson.reflect.TypeToken;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Persisted chat conversations (the sidebar history). One file
 * {@code ai/conversations.json}; messages are plain role/content pairs — the
 * system prompt is rebuilt at send time and never stored.
 */
public class AiConversationService {
    public static class Message {
        public String role = "user";
        public String content = "";

        public Message() {
        }

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class Conversation {
        public String id;
        public String title = "Hội thoại mới";
        public Instant updatedAt = Instant.now();
        public List<Message> messages = new ArrayList<>();

        public Conversation() {
            this.id = com.lifedesk.common.Ids.newId();
        }
    }

    private final JsonStore store;
    private final List<Conversation> conversations = new ArrayList<>();
    private final ChangeNotifier change = new ChangeNotifier();

    public AiConversationService(JsonStore store) {
        this.store = store;
        conversations.addAll(store.readList("ai/conversations.json", "conversations",
                new TypeToken<List<Conversation>>() {
                }.getType()));
    }

    public ChangeNotifier changes() {
        return change;
    }

    /** Newest first — the sidebar order. */
    public List<Conversation> list() {
        return conversations.stream()
                .sorted(Comparator.comparing((Conversation c) -> c.updatedAt).reversed())
                .toList();
    }

    public Optional<Conversation> get(String id) {
        return conversations.stream().filter(c -> c.id.equals(id)).findFirst();
    }

    /** Insert or refresh one conversation (caller owns the fields). */
    public void upsert(Conversation conversation) {
        conversation.updatedAt = Instant.now();
        get(conversation.id).ifPresentOrElse(existing -> {
            existing.title = conversation.title;
            existing.updatedAt = conversation.updatedAt;
            existing.messages = conversation.messages;
        }, () -> conversations.add(conversation));
        persist();
    }

    public void delete(String id) {
        conversations.removeIf(c -> c.id.equals(id));
        persist();
    }

    private void persist() {
        store.writeList("ai/conversations.json", "conversations", 1, conversations);
        change.fire();
    }
}
