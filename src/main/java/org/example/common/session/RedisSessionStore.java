package org.example.common.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Redis 会话存储实现
 * Key 格式: sba:session:{sessionId}
 * Value: Jackson 序列化的 JSON
 */
@Slf4j
@Service
public class RedisSessionStore implements SessionStore {

    private static final String SESSION_KEY_PREFIX = "sba:session:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisSessionStore(StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             @Value("${session.ttl:24h}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    @Override
    public void saveSession(String sessionId, SessionData data) {
        String key = buildKey(sessionId);
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (Exception e) {
            log.error("保存会话到 Redis 失败 - sessionId: {}", sessionId, e);
            throw e;
        }
    }

    @Override
    public Optional<SessionData> getSession(String sessionId) {
        String key = buildKey(sessionId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            // 滑动 TTL
            redisTemplate.expire(key, ttl);
            SessionData data = objectMapper.readValue(json, SessionData.class);
            return Optional.of(data);
        } catch (Exception e) {
            log.error("从 Redis 读取会话失败 - sessionId: {}", sessionId, e);
            throw e;
        }
    }

    @Override
    public void addMessageToSession(String sessionId, String role, String content) {
        String key = buildKey(sessionId);
        try {
            // 获取当前消息列表
            List<SessionData.MessageEntry> messages = getMessagesFromRedis(sessionId);

            // 追加新消息
            SessionData.MessageEntry entry = new SessionData.MessageEntry();
            entry.setRole(role);
            entry.setContent(content);
            messages.add(entry);

            // 保持 MAX_WINDOW_SIZE（每对消息包含2条记录）
            int maxMessages = 6 * 2;
            while (messages.size() > maxMessages) {
                messages.remove(0);
            }

            // 序列化并保存
            String json = objectMapper.writeValueAsString(messages);
            String msgListKey = buildMsgListKey(sessionId);
            redisTemplate.opsForValue().set(msgListKey, json, ttl);

            // 确保会话元数据也存在（滑动 TTL）
            getSession(sessionId).ifPresent(SessionStore.super::saveSession);
        } catch (Exception e) {
            log.error("向会话添加消息失败 - sessionId: {}", sessionId, e);
            throw e;
        }
    }

    @Override
    public List<MessageEntry> getMessages(String sessionId) {
        try {
            List<SessionData.MessageEntry> messages = getMessagesFromRedis(sessionId);
            // 滑动 TTL
            refreshTtl(sessionId);
            return convertToMessageEntries(messages);
        } catch (Exception e) {
            log.error("读取会话消息失败 - sessionId: {}", sessionId, e);
            throw e;
        }
    }

    @Override
    public void clearSession(String sessionId) {
        String key = buildKey(sessionId);
        String msgKey = buildMsgListKey(sessionId);
        redisTemplate.delete(key);
        redisTemplate.delete(msgKey);
        log.info("已清除会话 - sessionId: {}", sessionId);
    }

    @Override
    public boolean exists(String sessionId) {
        String key = buildKey(sessionId);
        Boolean hasKey = redisTemplate.hasKey(key);
        // 如果元数据不存在，检查消息列表
        if (!Boolean.TRUE.equals(hasKey)) {
            hasKey = redisTemplate.hasKey(buildMsgListKey(sessionId));
        }
        return Boolean.TRUE.equals(hasKey);
    }

    /**
     * 构建 Redis Key
     */
    private String buildKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    /**
     * 构建消息列表 Redis Key
     */
    private String buildMsgListKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId + ":msgs";
    }

    /**
     * 从 Redis 读取消息列表（内部方法，不暴露滑动 TTL）
     */
    private List<SessionData.MessageEntry> getMessagesFromRedis(String sessionId) {
        String msgKey = buildMsgListKey(sessionId);
        String json = redisTemplate.opsForValue().get(msgKey);
        if (json == null) {
            // 兼容旧数据：尝试从元数据中读取
            return getSession(sessionId)
                    .map(data -> {
                        // 创建新的消息列表key并开始追踪
                        return new ArrayList<SessionData.MessageEntry>();
                    })
                    .orElse(new ArrayList<>());
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class,
                            SessionData.MessageEntry.class));
        } catch (Exception e) {
            log.error("反序列化消息列表失败 - sessionId: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 刷新 TTL（滑动过期）
     */
    private void refreshTtl(String sessionId) {
        String key = buildKey(sessionId);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.expire(key, ttl);
        } else {
            // 只有消息列表时的降级场景
            String msgKey = buildMsgListKey(sessionId);
            if (Boolean.TRUE.equals(redisTemplate.hasKey(msgKey))) {
                redisTemplate.expire(msgKey, ttl);
            }
        }
    }

    private List<MessageEntry> convertToMessageEntries(List<SessionData.MessageEntry> entries) {
        List<MessageEntry> result = new ArrayList<>();
        for (SessionData.MessageEntry entry : entries) {
            MessageEntry me = new MessageEntry();
            me.setRole(entry.getRole());
            me.setContent(entry.getContent());
            result.add(me);
        }
        return result;
    }
}
