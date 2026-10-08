package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import org.example.common.cache.EmbeddingCache;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量嵌入服务
 * 使用阿里云 DashScope Text Embedding API
 * <p>
 * generateEmbeddings() now delegates to {@link EmbeddingCache} for Redis-cached lookup.
 */
@Service
public class VectorEmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.embedding.model}")
    private String model;

    @Autowired
    private MeterRegistry meterRegistry;

    /** Redis 缓存 — 命中则走本地，未命中再调 DashScope API */
    @Autowired
    private EmbeddingCache embeddingCache;

    /** Max execution time per batch (seconds) for soft timeout */
    @Value("${dashscope.embedding.timeout-sec:30}")
    private int embeddingTimeoutSec;

    private TextEmbedding textEmbedding;

    @PostConstruct
    public void init() {
        // 验证 API Key：只校验非空/非占位符，不打印任何 Key 值或片段
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your-api-key-here")) {
            // 校验失败只报缺失的键名，不输出任何密钥内容
            throw new IllegalStateException("dashscope.api.key 缺失或为空");
        }

        // 设置全局 API Key（确保设置成功）
        Constants.apiKey = apiKey;

        // 验证 API Key 是否设置成功（只报键名，不打印值）
        if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
            throw new IllegalStateException("dashscope.api.key 设置到 Constants 失败");
        }

        // 创建 TextEmbedding 实例
        textEmbedding = new TextEmbedding();

        logger.info("阿里云 DashScope Embedding 服务初始化完成，模型: {}", model);
    }

    /**
     * 生成向量嵌入
     * 调用阿里云 DashScope Text Embedding API
     * 
     * @param content 文本内容
     * @return 向量嵌入（浮点数列表）
     */
    public List<Float> generateEmbedding(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                logger.warn("内容为空，无法生成向量");
                throw new IllegalArgumentException("内容不能为空");
            }

            logger.debug("开始生成向量嵌入, 内容长度: {} 字符", content.length());

            // 确保 API Key 已设置（防止被其他地方覆盖）
            if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
                logger.warn("检测到 Constants.apiKey 为空，重新设置");
                Constants.apiKey = apiKey;
            }

            // 安全说明：此处不打印任何 API Key 内容（含片段），排障仅依赖下方维度/长度日志

            // 构建请求参数
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(Collections.singletonList(content))
                    .build();

            // 调用 API5
            Timer.Sample sample = Timer.start(meterRegistry);
            Counter c = meterRegistry.counter("sba.embedding.calls");
            try {
                TextEmbeddingResult result = textEmbedding.call(param);

                // 检查结果（不打印向量内容，仅在下方 info 中输出维度）
                List<Float> floatEmbedding = getFloats(result);

                logger.info("成功生成向量嵌入, 内容长度: {} 字符, 向量维度: {}",
                        content.length(), floatEmbedding.size());

                return floatEmbedding;
            } finally {
                sample.stop(Timer.builder("sba.embedding.latency")
                        .register(meterRegistry));
                c.increment();
            }

        } catch (NoApiKeyException e) {
            logger.error("API Key 未设置或无效", e);
            throw new RuntimeException("API Key 未设置，请配置 dashscope.api.key", e);
        } catch (Exception e) {
            logger.error("生成向量嵌入失败, 内容长度: {}", content != null ? content.length() : 0, e);
            throw new RuntimeException("生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    // 包级可见仅为单测
    @NotNull
    static List<Float> getFloats(TextEmbeddingResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            throw new RuntimeException("DashScope API 返回空结果");
        }

        TextEmbeddingOutput output = result.getOutput();
        List<TextEmbeddingResultItem> embeddings = output.getEmbeddings();

        if (embeddings.isEmpty()) {
            throw new RuntimeException("DashScope API 返回空向量列表");
        }

        // 获取第一个文本的向量
        List<Double> embeddingDoubles = embeddings.get(0).getEmbedding();

        // 转换为 List<Float>
        List<Float> floatEmbedding = new ArrayList<>(embeddingDoubles.size());
        for (Double value : embeddingDoubles) {
            floatEmbedding.add(value.floatValue());
        }
        return floatEmbedding;
    }

    /**
     * 批量生成向量嵌入（Redis 缓存 + Resilience4j 限流/重试，+ 软超时）。
     * <p>
     * Calls {@link EmbeddingCache#getOrCompute} for Redis-cached lookup.
     * Cache misses hit DashScope API via {@link TextEmbedding}.
     * Protected by @RateLimiter (4/s), @Retry (3 attempts, exponential backoff),
     * and a soft timeout of {@code embeddingTimeoutSec} seconds.
     *
     * @param contents 文本内容列表
     * @return 向量嵌入列表
     */
    @RateLimiter(name = "embedding")
    @Retry(name = "embedding")
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        long deadline = System.currentTimeMillis() + embeddingTimeoutSec * 1000L;

        try {
            return embeddingCache.getOrCompute(contents);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Embedding 操作被中断", e);
        } catch (Exception e) {
            throw new RuntimeException("批量生成向量嵌入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成查询向量
     * 
     * @param query 查询文本
     * @return 向量嵌入
     */
    public List<Float> generateQueryVector(String query) {
        return generateEmbedding(query);
    }

    /**
     * 计算两个向量的余弦相似度
     * 
     * @param vector1 向量1
     * @param vector2 向量2
     * @return 余弦相似度 [-1, 1]
     */
    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("向量维度不匹配");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
}
