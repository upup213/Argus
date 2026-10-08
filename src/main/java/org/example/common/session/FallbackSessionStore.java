package org.example.common.session;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFailureException;
import org.springframework.data.redis.serializer.RedisSystemException;
import org.springframework.stereotype.Service;

import javax.net.SocketException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储降级装饰器
 * 主后端为 Redis，当 Redis 不可用时自动降级到内存存储
 */
@Slf4j
public class FallbackSessionStore implements SessionStore {

    private final SessionStore primary;       // RedisSessionStore
    private final SessionStore fallback;      // InMemorySessionStore
    private volatile boolean degraded = false;
    private final MeterRegistry meterRegistry;

    public FallbackSessionStore(SessionStore primary,
                                SessionStore fallback,
                                MeterRegistry meterRegistry) {
        this.primary = primary;
        this.fallback = fallback;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void saveSession(String sessionId, SessionData data) {
        try {
            primary.saveSession(sessionId, data);
        } catch (Exception e) {
            handleFallback(e);
            fallback.saveSession(sessionId, data);
        }
    }

    @Override
    public Optional<SessionData> getSession(String sessionId) {
        try {
            return primary.getSession(sessionId);
        } catch (Exception e) {
            handleFallback(e);
            return fallback.getSession(sessionId);
        }
    }

    @Override
    public void addMessageToSession(String sessionId, String role, String content) {
        try {
            primary.addMessageToSession(sessionId, role, content);
        } catch (Exception e) {
            handleFallback(e);
            fallback.addMessageToSession(sessionId, role, content);
        }
    }

    @Override
    public List<MessageEntry> getMessages(String sessionId) {
        try {
            return primary.getMessages(sessionId);
        } catch (Exception e) {
            handleFallback(e);
            return fallback.getMessages(sessionId);
        }
    }

    @Override
    public void clearSession(String sessionId) {
        try {
            primary.clearSession(sessionId);
            fallback.clearSession(sessionId);
        } catch (Exception e) {
            handleFallback(e);
            fallback.clearSession(sessionId);
        }
    }

    @Override
    public boolean exists(String sessionId) {
        try {
            return primary.exists(sessionId);
        } catch (Exception e) {
            handleFallback(e);
            return fallback.exists(sessionId);
        }
    }

    private void handleFallback(Exception e) {
        if (isRedisException(e)) {
            if (!degraded) {
                degraded = true;
                log.error("Redis不可用，会话存储已降级到内存模式");
            }
            if (meterRegistry != null) {
                meterRegistry.counter("sba.redis.fallback").increment();
            }
        }
    }

    private boolean isRedisException(Exception e) {
        Throwable cause = e instanceof RuntimeException ? e.getCause() : e;
        return e instanceof RedisConnectionFailureException ||
               e instanceof RedisSystemException ||
               cause instanceof ConnectException ||
               cause instanceof SocketException;
    }

    /**
     * Bean 定义：默认使用 FallbackSessionStore（Redis + 内存降级）
     */
    @Bean
    @ConditionalOnProperty(name = "session.store", havingValue = "redis", matchIfMissing = true)
    public SessionStore sessionStore(
            RedisSessionStore redisSessionStore,
            InMemorySessionStore inMemorySessionStore,
            MeterRegistry meterRegistry) {
        return new FallbackSessionStore(redisSessionStore, inMemorySessionStore, meterRegistry);
    }
}
