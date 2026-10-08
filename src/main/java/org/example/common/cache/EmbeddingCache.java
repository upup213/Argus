package org.example.common.cache;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Embedding 结果 Redis 缓存
 * <p>
 * Key = "sba:cache:embedding:" + SHA-256(text).hex
 * Value = JSON array of embedding vectors for that text
 * TTL configurable via cache.embedding.ttl (default 7 days)
 *
 * Uses {@link TextEmbedding} directly to avoid coupling with VectorEmbeddingService.
 */
@Component
public class EmbeddingCache {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingCache.class);

    private static final String CACHE_PREFIX = "sba:cache:embedding:";
    private static final int BATCH_SIZE = 32; // safe batch size for Redis write

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TextEmbedding textEmbedding;
    private final Duration ttl;

    public EmbeddingCache(StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper,
                          TextEmbedding textEmbedding,
                          @Value("${cache.embedding.ttl:7d}") String ttlStr) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.textEmbedding = textEmbedding;
        this.ttl = parseDuration(ttlStr);
    }

    /**
     * Get cached embeddings or compute missing ones.
     * Falls back to TextEmbedding API for cache misses.
     */
    public List<List<Float>> getOrCompute(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        // Compute hash for each text and check cache in parallel
        Map<Integer, String> hashes = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            hashes.put(i, sha256hex(texts.get(i)));
        }

        Map<Integer, List<List<Float>>> cacheHits = new LinkedHashMap<>();
        List<Integer> missIndices = new ArrayList<>();

        // Async lookups for all keys
        Map<String, CompletableFuture<String>> futures = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : hashes.entrySet()) {
            String key = CACHE_PREFIX + entry.getValue();
            futures.put(entry.getKey().toString(), redisTemplate.opsForValue().getAsync(key).toCompletableFuture());
        }

        int hitsCounter = 0, missesCounter = 0;
        for (Map.Entry<Integer, String> entry : hashes.entrySet()) {
            String val = futures.get(entry.getKey().toString()).get();
            if (val != null && !val.isEmpty()) {
                List<List<Float>> vec = objectMapper.readValue(val, new TypeReference<List<List<Float>>() {}>);
                cacheHits.put(entry.getKey(), vec);
                hitsCounter++;
            } else {
                missIndices.add(entry.getKey());
                missesCounter++;
            }
        }

        // For cache misses, call TextEmbedding API per text
        if (!missIndices.isEmpty()) {
            List<String> missTexts = missIndices.stream()
                    .map(i -> texts.get(i))
                    .collect(Collectors.toList());

            List<List<Float>> missVectors;
            if (missTexts.size() <= BATCH_SIZE) {
                missVectors = textEmbedding.call(missTexts);
            } else {
                missVectors = new ArrayList<>();
                for (int i = 0; i < missTexts.size(); i += BATCH_SIZE) {
                    List<String> sub = missTexts.subList(i, Math.min(i + BATCH_SIZE, missTexts.size()));
                    missVectors.addAll(textEmbedding.call(sub));
                }
            }

            // Store locally and persist to Redis
            int idx = 0;
            for (int i : missIndices) {
                List<List<Float>> vec = missVectors.get(idx++);
                cacheHits.put(i, vec);
                String key = CACHE_PREFIX + hashes.get(i);
                String jsonVal = objectMapper.writeValueAsString(vec);
                redisTemplate.opsForValue().set(key, jsonVal, ttl);
            }
        }

        // Reconstruct result in original order
        List<List<Float>> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            result.add(cacheHits.getOrDefault(i, null));
        }

        logger.info("Embedding cache hit/miss: {}/{}", hitsCounter, missesCounter);
        return result;
    }

    // ---------- helpers ----------

    private static Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        long v = 0;
        String unit = "";
        // Handle simple patterns like "7d", "2h", "30m"
        int len = s.length();
        while (len > 0 && Character.isDigit(s.charAt(len - 1))) {
            len--;
        }
        if (len > 0) {
            unit = s.substring(len).trim();
        }
        try {
            v = Long.parseLong(s.substring(0, len).trim());
        } catch (NumberFormatException e) {
            v = 7;
            unit = "d";
        }
        return switch (unit) {
            case "h" -> Duration.ofHours(v);
            case "m" -> Duration.ofMinutes(v);
            case "s" -> Duration.ofSeconds(v);
            default -> Duration.ofDays(v); // "d" or unknown → treat as days
        };
    }

    /**
     * Hex-encoded SHA-256 digest.
     */
    private static String sha256hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }
}
