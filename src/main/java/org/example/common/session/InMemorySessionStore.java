package org.example.common.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存会话存储实现（用于演示/降级模式）
 * 基于 ConcurrentHashMap，无 TTL 管理（由 ChatController 定时清理负责）
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "session.store", havingValue = "memory")
public class InMemorySessionStore implements SessionStore {

    private final Map<String, SessionData> sessionMetaStore = new ConcurrentHashMap<>();
    private final Map<String, List<MessageEntry>> messageStore = new ConcurrentHashMap<>();

    @Override
    public void saveSession(String sessionId, SessionData data) {
        sessionMetaStore.put(sessionId, data);
        // 首次保存时初始化消息列表
        messageStore.computeIfAbsent(sessionId, k -> new ArrayList<>());
    }

    @Override
    public Optional<SessionData> getSession(String sessionId) {
        return Optional.ofNullable(sessionMetaStore.get(sessionId));
    }

    @Override
    public void addMessageToSession(String sessionId, String role, String content) {
        List<MessageEntry> messages = messageStore.computeIfAbsent(sessionId, k -> new ArrayList<>());

        MessageEntry entry = new MessageEntry();
        entry.setRole(role);
        entry.setContent(content);
        messages.add(entry);

        // 保持 MAX_WINDOW_SIZE 限制
        int maxMessages = 6 * 2;
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
    }

    @Override
    public List<MessageEntry> getMessages(String sessionId) {
        List<MessageEntry> messages = messageStore.get(sessionId);
        if (messages == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    @Override
    public void clearSession(String sessionId) {
        sessionMetaStore.remove(sessionId);
        messageStore.remove(sessionId);
        log.info("已清除内存会话 - sessionId: {}", sessionId);
    }

    @Override
    public boolean exists(String sessionId) {
        return sessionMetaStore.containsKey(sessionId) || messageStore.containsKey(sessionId);
    }
}
