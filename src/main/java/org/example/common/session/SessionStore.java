package org.example.common.session;

import lombok.Data;

import java.util.List;
import java.util.Optional;

/**
 * 会话存储接口
 * 支持 Redis / 内存 / 降级等多种后端实现
 */
public interface SessionStore {

    /**
     * 保存会话元数据
     */
    void saveSession(String sessionId, SessionData data);

    /**
     * 获取会话元数据
     */
    Optional<SessionData> getSession(String sessionId);

    /**
     * 向会话追加一条消息
     */
    void addMessageToSession(String sessionId, String role, String content);

    /**
     * 获取会话的所有历史消息
     * 格式: [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}]
     */
    List<MessageEntry> getMessages(String sessionId);

    /**
     * 清空会话（删除所有数据和消息）
     */
    void clearSession(String sessionId);

    /**
     * 检查会话是否存在
     */
    boolean exists(String sessionId);

    @Data
    class SessionData {
        private String sessionId;
        private long createTime;
    }

    @Data
    class MessageEntry {
        private String role;
        private String content;
    }
}
